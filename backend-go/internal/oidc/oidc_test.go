package oidc

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func testProvider(t *testing.T) (*Provider, *http.ServeMux) {
	t.Helper()
	p, err := NewProvider("http://127.0.0.1:8445", Tenant{
		TenantID: "local-tenant", TenantName: "Local", TenantURL: "http://127.0.0.1:8787",
		UserID: "local-user", Email: "local@augment.local", DisplayName: "Local User", Plan: "professional",
	}, "")
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	p.Register(mux)
	return p, mux
}

func TestWellKnown(t *testing.T) {
	_, mux := testProvider(t)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("GET", "/.well-known/openid-configuration", nil))
	if rec.Code != 200 {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	var d map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &d); err != nil {
		t.Fatal(err)
	}
	if d["issuer"] != "http://127.0.0.1:8445" {
		t.Errorf("issuer = %v", d["issuer"])
	}
	if d["token_endpoint"] != "http://127.0.0.1:8445/oauth/token" {
		t.Errorf("token_endpoint = %v", d["token_endpoint"])
	}
}

// TestTokenRoundTrip runs authorize -> token and asserts the JWT carries the
// tenant claims the plugin needs (tenantUrl/tenantId) as well as the standard
// OIDC claims.
func TestTokenRoundTrip(t *testing.T) {
	_, mux := testProvider(t)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("GET",
		"/authorize?autologin=1&redirect_uri=http://127.0.0.1:59999/augment&state=s1", nil))
	if rec.Code != 302 {
		t.Fatalf("authorize status = %d, want 302", rec.Code)
	}
	loc := rec.Header().Get("Location")
	if !strings.Contains(loc, "code=") {
		t.Fatalf("no code in redirect: %s", loc)
	}
	code := strings.Split(strings.Split(loc, "code=")[1], "&")[0]

	tokRec := httptest.NewRecorder()
	mux.ServeHTTP(tokRec, httptest.NewRequest("POST",
		"/oauth/token?grant_type=authorization_code&code="+code+"&redirect_uri=http://127.0.0.1:59999/augment", nil))
	if tokRec.Code != 200 {
		t.Fatalf("token status = %d: %s", tokRec.Code, tokRec.Body.String())
	}
	var tok struct {
		AccessToken string `json:"access_token"`
		TenantURL   string `json:"tenantUrl"`
		TenantID    string `json:"tenantId"`
		IDToken     string `json:"id_token"`
	}
	if err := json.Unmarshal(tokRec.Body.Bytes(), &tok); err != nil {
		t.Fatal(err)
	}
	if tok.AccessToken == "" {
		t.Fatal("empty access_token")
	}
	if tok.TenantURL != "http://127.0.0.1:8787" || tok.TenantID != "local-tenant" {
		t.Errorf("tenant claims wrong: %+v", tok)
	}
	if tok.IDToken == "" {
		t.Error("missing id_token")
	}
	if strings.Count(tok.AccessToken, ".") != 2 {
		t.Error("access_token is not a compact JWT")
	}
}

// TestPKCE verifies the S256 challenge flow the JetBrains OAuth client uses.
func TestPKCE(t *testing.T) {
	_, mux := testProvider(t)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("GET",
		"/authorize?autologin=1&redirect_uri=http://127.0.0.1:59999/augment&state=s&code_challenge=abc123&code_challenge_method=S256", nil))
	loc := rec.Header().Get("Location")
	code := strings.Split(strings.Split(loc, "code=")[1], "&")[0]

	// Wrong verifier -> reject.
	bad := httptest.NewRecorder()
	mux.ServeHTTP(bad, httptest.NewRequest("POST",
		"/oauth/token?grant_type=authorization_code&code="+code+"&code_verifier=wrong", nil))
	if bad.Code != 400 {
		t.Errorf("bad verifier status = %d, want 400", bad.Code)
	}

	// Right verifier (sha256("correct") url-b64 == "abc123" only if it really
	// is; here we re-issue with a verifier that matches the challenge).
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, httptest.NewRequest("GET",
		"/authorize?autologin=1&redirect_uri=http://127.0.0.1:59999/augment&state=s&code_challenge="+SHA256B64("secret")+"&code_challenge_method=S256", nil))
	code2 := strings.Split(strings.Split(rec2.Header().Get("Location"), "code=")[1], "&")[0]
	good := httptest.NewRecorder()
	mux.ServeHTTP(good, httptest.NewRequest("POST",
		"/oauth/token?grant_type=authorization_code&code="+code2+"&code_verifier=secret", nil))
	if good.Code != 200 {
		t.Errorf("good verifier status = %d, want 200: %s", good.Code, good.Body.String())
	}
}
