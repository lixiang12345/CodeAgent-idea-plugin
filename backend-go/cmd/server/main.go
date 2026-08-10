// Command server runs the two-port Augment local backend:
//
//	:8445  OIDC IdP (discovery / jwks / authorize / token) — point the IDE here
//	       with -Daugmentcode.oauth.url=http://127.0.0.1:8445
//	:8787  tenant surface — /api-client/* REST, connect/gRPC, discovery, chat
//
// Env:
//
//	OIDC_ADDR       listen addr for the IdP       (default :8445)
//	TENANT_ADDR     listen addr for the tenant    (default :8787)
//	OIDC_URL        externally-reachable IdP URL  (default http://127.0.0.1:8445)
//	TENANT_URL      externally-reachable tenant   (default http://127.0.0.1:8787)
//	JWT_PRIVATE_KEY PKCS#8/PKCS#1 RSA PEM for the IdP (optional; ephemeral otherwise)
//	MODEL_GATEWAY_URL OpenAI-compatible upstream for real chat (optional)
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"augment-local/internal/oidc"
	"augment-local/internal/tenant"

	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
)

func main() {
	oidcAddr := envOr("OIDC_ADDR", ":8445")
	tenantAddr := envOr("TENANT_ADDR", ":8787")
	oidcURL := envOr("OIDC_URL", "http://127.0.0.1:8445")
	tenantURL := envOr("TENANT_URL", "http://127.0.0.1:8787")
	gatewayURL := os.Getenv("MODEL_GATEWAY_URL")
	gatewayModel := os.Getenv("MODEL_GATEWAY_MODEL")
	if gatewayModel == "" {
		gatewayModel = "augment-local"
	}

	flag.Parse()

	idp, err := oidc.NewProvider(oidcURL, oidc.Tenant{
		TenantID:   envOr("TENANT_ID", "local-tenant"),
		TenantName: envOr("TENANT_NAME", "Augment Local"),
		TenantURL:  tenantURL,
		UserID:     envOr("USER_ID", "local-user"),
		Email:      envOr("USER_EMAIL", "local@augment.local"),
		DisplayName: envOr("USER_NAME", "Local User"),
		Plan:       envOr("USER_PLAN", "professional"),
	}, os.Getenv("JWT_PRIVATE_KEY"))
	if err != nil {
		log.Fatalf("oidc: %v", err)
	}

	oidcMux := http.NewServeMux()
	idp.Register(oidcMux)

	ten := tenant.New(tenantURL, gatewayURL, gatewayModel)
	ten.TokenHandler = func(w http.ResponseWriter, r *http.Request) {
		log.Printf("tenant: %s %s (TokenHandler) content-type=%s", r.Method, r.URL.Path, r.Header.Get("Content-Type"))
		idp.IssueToken(w)
	}

	srv := func(addr string, h http.Handler) *http.Server {
		return &http.Server{Addr: addr, Handler: h, ReadHeaderTimeout: 10 * time.Second}
	}
	srvOIDC := srv(oidcAddr, cors(oidcMux))
	// The tenant handler accepts both HTTP/1.1 (REST, connect+json) and
	// plaintext HTTP/2 (gRPC) via h2c, so gRPC clients (grpcurl, grpc-js)
	// can address the same port.
	h2s := &http2.Server{}
	srvTenant := srv(tenantAddr, h2c.NewHandler(ten.Handler(), h2s))

	go func() {
		log.Printf("oidc IdP listening on %s (point IDE here via -Daugmentcode.oauth.url=%s)", oidcAddr, oidcURL)
		if err := srvOIDC.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("oidc server: %v", err)
		}
	}()
	go func() {
		log.Printf("tenant surface listening on %s (tenantUrl=%s)", tenantAddr, tenantURL)
		if err := srvTenant.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("tenant server: %v", err)
		}
	}()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
	log.Println("shutting down…")
	shut, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srvOIDC.Shutdown(shut)
	_ = srvTenant.Shutdown(shut)
}


func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Access-Control-Allow-Origin", "*")
		h.Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
		h.Set("Access-Control-Allow-Headers", "Authorization, Content-Type, Connect-Protocol-Version, Grpc-Timeout, X-Request-Id, Accept, Accept-Encoding")
		h.Set("Access-Control-Expose-Headers", "Grpc-Status, Grpc-Message, Connect-Protocol-Version")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}
func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
