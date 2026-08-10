// Package oidc implements the local Identity Provider the Augment plugin's
// OAuthServiceBase flow talks to. It mirrors the shape of auth.augmentcode.com:
// an OIDC discovery document, a JWKS endpoint, an authorize page, and a token
// endpoint that issues a signed JWT carrying the tenant claims the plugin needs
// (tenantId, tenantName, tenantUrl) to redirect ALL cloud traffic to our tenant
// surface. No hosts file edits, no CA installation: the IDE is pointed here via
// -Daugmentcode.oauth.url=<OIDC_URL>.
package oidc

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const kid = "augment-local-1"

// Tenant describes the identity the plugin receives after login. tenantUrl is
// the address of the tenant surface that will serve all /api-client/* calls.
type Tenant struct {
	TenantID   string `json:"tenantId"`
	TenantName string `json:"tenantName"`
	TenantURL  string `json:"tenantUrl"`
	UserID     string `json:"id"`
	Email      string `json:"email"`
	DisplayName string `json:"name"`
	Plan       string `json:"plan"`
}

// Provider is the local OIDC authority.
type Provider struct {
	baseURL  string
	tenant   Tenant
	key      *rsa.PrivateKey
	pubJWK   map[string]any
	codes    sync.Map // code -> redirectURI
	mu       sync.Mutex
	now      func() time.Time
}

// NewProvider builds a Provider, loading an RSA key from oidcPrivateKeyPEM if
// given (PEM "RSA PRIVATE KEY" or "PRIVATE KEY"), otherwise generating an
// ephemeral 2048-bit key at startup. Ephemeral keys are fine: JWKS is served
// from the same process.
func NewProvider(baseURL string, tenant Tenant, oidcPrivateKeyPEM string) (*Provider, error) {
	key, err := loadOrGenKey(oidcPrivateKeyPEM)
	if err != nil {
		return nil, err
	}
	n := base64.RawURLEncoding.EncodeToString(key.PublicKey.N.Bytes())
	e := base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.PublicKey.E)).Bytes())
	p := &Provider{
		baseURL: strings.TrimRight(baseURL, "/"),
		tenant:  tenant,
		key:     key,
		pubJWK: map[string]any{
			"kty": "RSA", "kid": kid, "use": "sig", "alg": "RS256",
			"n": n, "e": e,
		},
		now: time.Now,
	}
	return p, nil
}

func loadOrGenKey(pemStr string) (*rsa.PrivateKey, error) {
	if pemStr == "" {
		k, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			return nil, fmt.Errorf("generate rsa key: %w", err)
		}
		return k, nil
	}
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, fmt.Errorf("invalid PEM in JWT_PRIVATE_KEY")
	}
	if k, err := parsePKCS1(block); err == nil {
		return k, nil
	}
	if k, err := parsePKCS8(block); err == nil {
		return k, nil
	}
	return nil, fmt.Errorf("unsupported private key block %q", block.Type)
}

func parsePKCS1(block *pem.Block) (*rsa.PrivateKey, error) {
	if block.Type != "RSA PRIVATE KEY" {
		return nil, fmt.Errorf("not pkcs1")
	}
	return x509ParsePKCS1(block.Bytes)
}

func parsePKCS8(block *pem.Block) (*rsa.PrivateKey, error) {
	if block.Type != "PRIVATE KEY" {
		return nil, fmt.Errorf("not pkcs8")
	}
	return x509ParsePKCS8(block.Bytes)
}

// Register mounts the OIDC endpoints on mux.
func (p *Provider) Register(mux *http.ServeMux) {
	mux.HandleFunc("/.well-known/openid-configuration", p.logWrap("well-known", p.handleWellKnown))
	mux.HandleFunc("/.well-known/jwks.json", p.logWrap("jwks", p.handleJWKS))
	mux.HandleFunc("/jwks.json", p.logWrap("jwks", p.handleJWKS))
	mux.HandleFunc("/authorize", p.logWrap("authorize", p.handleAuthorize))
	mux.HandleFunc("/oauth/token", p.logWrap("token", p.handleToken))
	mux.HandleFunc("/token", p.logWrap("token", p.handleToken))
	mux.HandleFunc("/userinfo", p.logWrap("userinfo", p.handleUserInfo))
	mux.HandleFunc("/api/augment/auth/result", p.logWrap("auth-result", p.handleAuthResult))
	mux.HandleFunc("/logout", func(w http.ResponseWriter, r *http.Request) {
		log.Printf("oidc: %s /logout", r.Method)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
}

func (p *Provider) logWrap(name string, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		log.Printf("oidc: %s /%s %s", r.Method, name, r.URL.RawQuery)
		h.ServeHTTP(w, r)
	}
}
func (p *Provider) handleWellKnown(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"issuer":                                p.baseURL,
		"authorization_endpoint":                p.baseURL + "/authorize",
		"token_endpoint":                        p.baseURL + "/oauth/token",
		"userinfo_endpoint":                     p.baseURL + "/userinfo",
		"jwks_uri":                              p.baseURL + "/jwks.json",
		"response_types_supported":              []string{"code"},
		"subject_types_supported":               []string{"public"},
		"id_token_signing_alg_values_supported": []string{"RS256"},
		"scopes_supported":                      []string{"openid", "profile", "email", "offline_access"},
		"grant_types_supported":                 []string{"authorization_code", "refresh_token", "client_credentials"},
		"token_endpoint_auth_methods_supported": []string{"client_secret_post", "none"},
		"code_challenge_methods_supported":      []string{"S256", "plain"},
		"claims_supported":                      []string{"sub", "email", "name", "tenantId", "tenantName", "tenantUrl", "plan"},
	})
}

func (p *Provider) handleJWKS(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"keys": []map[string]any{p.pubJWK},
	})
}

func (p *Provider) handleUserInfo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"sub":        p.tenant.UserID,
		"email":      p.tenant.Email,
		"name":       p.tenant.DisplayName,
		"tenantId":   p.tenant.TenantID,
		"tenantName": p.tenant.TenantName,
		"plan":       p.tenant.Plan,
	})
}

func (p *Provider) handleAuthResult(w http.ResponseWriter, r *http.Request) {
	// Landing page the browser hits after the flow completes. Closes itself so
	// the IDE's OAuthServiceBase can resume.
	writeHTML(w, `<html><body><script>window.close();</script><p style="font-family:system-ui,sans-serif">Augment local sign-in complete. You can close this tab.</p></body></html>`)
}

// handleAuthorize auto-completes the OAuth authorization: the IDE has already
// opened the browser — we send the code immediately and redirect back to the
// plugin's redirect_uri. No user interaction needed; the whole flow completes
// in one HTTP round-trip.
func (p *Provider) handleAuthorize(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	redirectURI := q.Get("redirect_uri")
	state := q.Get("state")
	log.Printf("oidc: authorize GET, redirect_uri=%s state=%s code_challenge=%t", redirectURI, state, q.Get("code_challenge") != "")
	if redirectURI == "" {
		http.Error(w, "missing redirect_uri", http.StatusBadRequest)
		return
	}
	// Always auto-complete — same code path for GET, POST, and autologin.
	p.completeAuthorize(w, r, redirectURI, state, q.Get("code_challenge"), q.Get("code_challenge_method"))
}

func (p *Provider) completeAuthorize(w http.ResponseWriter, r *http.Request, redirectURI, state, cc, ccm string) {
	log.Printf("oidc: completeAuthorize -> redirect to %s (code sent)", redirectURI)
	code := randomToken(24)
	val := map[string]any{
		"redirect_uri":  redirectURI,
		"state":         state,
		"code_challenge": cc,
		"ccm":            ccm,
		"exp":            time.Now().Add(10 * time.Minute).Unix(),
	}
	raw, _ := json.Marshal(val)
	p.codes.Store(code, string(raw))

	u, err := url.Parse(redirectURI)
	if err != nil {
		http.Error(w, "bad redirect_uri", http.StatusBadRequest)
		return
	}
	query := u.Query()
	query.Set("code", code)
	if state != "" {
		query.Set("state", state)
	}
	// The IDE OAuthServiceBase extracts tenant_url from the redirect.
	query.Set("tenant_url", p.tenant.TenantURL)
	u.RawQuery = query.Encode()
	http.Redirect(w, r, u.String(), http.StatusFound)
}

func (p *Provider) handleToken(w http.ResponseWriter, r *http.Request) {
	log.Printf("oidc: token request content-type=%s", r.Header.Get("Content-Type"))
	var grantType, code, redirectURI, verifier string
	switch r.Header.Get("Content-Type") {
	case "application/json":
		var body struct {
			GrantType    string `json:"grant_type"`
			Code         string `json:"code"`
			RedirectURI  string `json:"redirect_uri"`
			CodeVerifier string `json:"code_verifier"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err == nil {
			grantType, code, redirectURI, verifier = body.GrantType, body.Code, body.RedirectURI, body.CodeVerifier
		}
	default:
		_ = r.ParseForm()
		grantType = r.FormValue("grant_type")
		code = r.FormValue("code")
		redirectURI = r.FormValue("redirect_uri")
		verifier = r.FormValue("code_verifier")
	}
	if grantType == "" {
		grantType = "authorization_code"
	}

	if grantType == "authorization_code" {
		if code == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_request", "error_description": "missing code"})
			return
		}
		raw, ok := p.codes.LoadAndDelete(code)
		if !ok {
			// Be lenient: a fresh install may replay a stale code or the IDE may
			// use a code it got from a previous provider run. Issue anyway.
			log.Printf("oidc: unknown auth code %q (lenient issue)", code)
		} else {
			var val map[string]any
			_ = json.Unmarshal([]byte(raw.(string)), &val)
			// Cross-check the redirect_uri the token request claims against the
			// one bound at authorize time when the plugin sends both.
			if stored, _ := val["redirect_uri"].(string); stored != "" && redirectURI != "" && stored != redirectURI {
				log.Printf("oidc: redirect_uri mismatch (have %q, stored %q) — lenient issue", redirectURI, stored)
			}
			if cc, _ := val["code_challenge"].(string); cc != "" && verifier != "" {
				if !pkceValid(cc, val["ccm"].(string), verifier) {
					writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_grant", "error_description": "PKCE verification failed"})
					return
				}
			}
		}
	}
	p.IssueToken(w)
}

func pkceValid(challenge, method, verifier string) bool {
	if method == "plain" {
		return challenge == verifier
	}
	sum := sha256.Sum256([]byte(verifier))
	return challenge == base64.RawURLEncoding.EncodeToString(sum[:])
}

// IssueToken writes a full token response to w. Exported so the
// tenant surface can serve its own /token endpoint.
func (p *Provider) IssueToken(w http.ResponseWriter) {
	now := p.now()
	access := p.SignJWT(map[string]any{
		"iss": p.baseURL, "sub": p.tenant.UserID, "aud": "augment",
		"iat": now.Unix(), "exp": now.Add(24 * time.Hour).Unix(), "jti": randomToken(12),
		"email": p.tenant.Email, "name": p.tenant.DisplayName,
		"tenantId": p.tenant.TenantID, "tenantName": p.tenant.TenantName,
		"tenantUrl": p.tenant.TenantURL, "plan": p.tenant.Plan,
	})
	idTok := p.SignJWT(map[string]any{
		"iss": p.baseURL, "sub": p.tenant.UserID, "aud": "augment",
		"iat": now.Unix(), "exp": now.Add(24 * time.Hour).Unix(),
		"email": p.tenant.Email, "name": p.tenant.DisplayName,
		"tenantId": p.tenant.TenantID, "tenantName": p.tenant.TenantName,
		"tenantUrl": p.tenant.TenantURL,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"access_token":  access,
		"id_token":      idTok,
		"token_type":    "Bearer",
		"expires_in":    86400,
		"refresh_token": randomToken(32),
		"scope":         "openid profile email",
		// Top-level convenience fields (non-standard, in addition to the JWT
		// claims) so a plugin reading the raw token response also sees them.
		"tenantId":   p.tenant.TenantID,
		"tenantName": p.tenant.TenantName,
		"tenantUrl":  p.tenant.TenantURL,
		"user": map[string]any{
			"id": p.tenant.UserID, "email": p.tenant.Email, "name": p.tenant.DisplayName,
		},
	})
}

// SignJWT produces a compact RS256 JWT with the given claims.
func (p *Provider) SignJWT(claims map[string]any) string {
	header := map[string]any{"alg": "RS256", "typ": "JWT", "kid": kid}
	hb, _ := json.Marshal(header)
	pb, _ := json.Marshal(claims)
	seg := base64.RawURLEncoding.EncodeToString(hb) + "." + base64.RawURLEncoding.EncodeToString(pb)
	digest := sha256.Sum256([]byte(seg))
	sig, err := rsa.SignPKCS1v15(rand.Reader, p.key, crypto.SHA256, digest[:])
	if err != nil {
		log.Printf("oidc: sign failed: %v", err)
		return ""
	}
	return seg + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeHTML(w http.ResponseWriter, body string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(body))
}
