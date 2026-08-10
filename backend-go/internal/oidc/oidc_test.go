package oidc

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const testRedirectURI = "http://127.0.0.1:59999/augment"

var testVerifier = strings.Repeat("a", 43)

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
	methods, ok := d["code_challenge_methods_supported"].([]any)
	if !ok || len(methods) != 1 || methods[0] != "S256" {
		t.Errorf("code_challenge_methods_supported = %#v, want only S256", d["code_challenge_methods_supported"])
	}
	grants, ok := d["grant_types_supported"].([]any)
	if !ok || len(grants) != 1 || grants[0] != "authorization_code" {
		t.Errorf("grant_types_supported = %#v, want only authorization_code", d["grant_types_supported"])
	}
}

func authorizeCode(t *testing.T, mux http.Handler, verifier string) string {
	t.Helper()
	q := url.Values{
		"response_type":         {"code"},
		"client_id":             {"augment-intellij-plugin"},
		"redirect_uri":          {testRedirectURI},
		"state":                 {"test-state"},
		"code_challenge":        {SHA256B64(verifier)},
		"code_challenge_method": {"S256"},
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/authorize?"+q.Encode(), nil))
	if rec.Code != http.StatusFound {
		t.Fatalf("authorize status = %d, want 302: %s", rec.Code, rec.Body.String())
	}
	location, err := url.Parse(rec.Header().Get("Location"))
	if err != nil {
		t.Fatalf("parse authorize redirect: %v", err)
	}
	if got := location.Query().Get("state"); got != "test-state" {
		t.Fatalf("redirect state = %q, want test-state", got)
	}
	code := location.Query().Get("code")
	if code == "" {
		t.Fatalf("authorize redirect missing code: %s", location)
	}
	return code
}

func exchangeCode(mux http.Handler, code, redirectURI, verifier string) *httptest.ResponseRecorder {
	body := url.Values{
		"grant_type":    {"authorization_code"},
		"code":          {code},
		"redirect_uri":  {redirectURI},
		"code_verifier": {verifier},
	}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/oauth/token", strings.NewReader(body.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	mux.ServeHTTP(rec, req)
	return rec
}

func requireOAuthError(t *testing.T, rec *httptest.ResponseRecorder, want string) {
	t.Helper()
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400: %s", rec.Code, rec.Body.String())
	}
	var got struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode OAuth error: %v", err)
	}
	if got.Error != want {
		t.Fatalf("OAuth error = %q, want %q: %s", got.Error, want, rec.Body.String())
	}
}

// TestTokenRoundTrip runs authorize -> token and asserts the JWT carries the
// tenant claims the plugin needs (tenantUrl/tenantId) as well as the standard
// OIDC claims.
func TestTokenRoundTrip(t *testing.T) {
	_, mux := testProvider(t)
	code := authorizeCode(t, mux, testVerifier)
	tokRec := exchangeCode(mux, code, testRedirectURI, testVerifier)
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

func TestTokenAcceptsJSONContentTypeWithCharset(t *testing.T) {
	_, mux := testProvider(t)
	code := authorizeCode(t, mux, testVerifier)
	body, err := json.Marshal(map[string]string{
		"grant_type":    "authorization_code",
		"code":          code,
		"redirect_uri":  testRedirectURI,
		"code_verifier": testVerifier,
	})
	if err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/oauth/token", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("token status = %d: %s", rec.Code, rec.Body.String())
	}
}

func TestAuthorizeRequiresS256PKCE(t *testing.T) {
	_, mux := testProvider(t)
	tests := []struct {
		name   string
		params url.Values
	}{
		{name: "missing response type", params: url.Values{"redirect_uri": {testRedirectURI}, "code_challenge": {SHA256B64(testVerifier)}, "code_challenge_method": {"S256"}}},
		{name: "missing challenge", params: url.Values{"response_type": {"code"}, "redirect_uri": {testRedirectURI}}},
		{name: "missing method", params: url.Values{"response_type": {"code"}, "redirect_uri": {testRedirectURI}, "code_challenge": {SHA256B64(testVerifier)}}},
		{name: "plain method", params: url.Values{"response_type": {"code"}, "redirect_uri": {testRedirectURI}, "code_challenge": {testVerifier}, "code_challenge_method": {"plain"}}},
		{name: "malformed S256 challenge", params: url.Values{"response_type": {"code"}, "redirect_uri": {testRedirectURI}, "code_challenge": {"not-a-sha256-digest"}, "code_challenge_method": {"S256"}}},
		{name: "external redirect", params: url.Values{"response_type": {"code"}, "redirect_uri": {"https://attacker.example/callback"}, "code_challenge": {SHA256B64(testVerifier)}, "code_challenge_method": {"S256"}}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/authorize?"+tt.params.Encode(), nil))
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400", rec.Code)
			}
		})
	}
}

func TestAuthorizationCodeIsSingleUse(t *testing.T) {
	_, mux := testProvider(t)
	code := authorizeCode(t, mux, testVerifier)
	if rec := exchangeCode(mux, code, testRedirectURI, testVerifier); rec.Code != http.StatusOK {
		t.Fatalf("first exchange status = %d: %s", rec.Code, rec.Body.String())
	}
	replay := exchangeCode(mux, code, testRedirectURI, testVerifier)
	requireOAuthError(t, replay, "invalid_grant")
}

func TestAuthorizationCodeConcurrentExchangeOnlyOneSucceeds(t *testing.T) {
	_, mux := testProvider(t)
	code := authorizeCode(t, mux, testVerifier)

	const attempts = 8
	start := make(chan struct{})
	var wg sync.WaitGroup
	var successes atomic.Int32
	var rejected atomic.Int32
	for range attempts {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			rec := exchangeCode(mux, code, testRedirectURI, testVerifier)
			switch rec.Code {
			case http.StatusOK:
				successes.Add(1)
			case http.StatusBadRequest:
				rejected.Add(1)
			default:
				t.Errorf("exchange status = %d: %s", rec.Code, rec.Body.String())
			}
		}()
	}
	close(start)
	wg.Wait()
	if successes.Load() != 1 || rejected.Load() != attempts-1 {
		t.Fatalf("concurrent exchanges: successes=%d rejected=%d", successes.Load(), rejected.Load())
	}
}

func TestAuthorizationCodeValidationFailureConsumesCode(t *testing.T) {
	tests := []struct {
		name        string
		redirectURI string
		verifier    string
	}{
		{name: "missing redirect URI", redirectURI: "", verifier: testVerifier},
		{name: "redirect URI mismatch", redirectURI: "http://127.0.0.1:59999/different", verifier: testVerifier},
		{name: "missing verifier", redirectURI: testRedirectURI, verifier: ""},
		{name: "invalid verifier characters", redirectURI: testRedirectURI, verifier: strings.Repeat("a", 42) + "!"},
		{name: "verifier mismatch", redirectURI: testRedirectURI, verifier: strings.Repeat("b", 43)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, mux := testProvider(t)
			code := authorizeCode(t, mux, testVerifier)
			first := exchangeCode(mux, code, tt.redirectURI, tt.verifier)
			requireOAuthError(t, first, "invalid_grant")

			replay := exchangeCode(mux, code, testRedirectURI, testVerifier)
			requireOAuthError(t, replay, "invalid_grant")
		})
	}
}

func TestExpiredAuthorizationCodeIsConsumed(t *testing.T) {
	p, mux := testProvider(t)
	issuedAt := time.Date(2026, 8, 10, 9, 0, 0, 0, time.UTC)
	p.now = func() time.Time { return issuedAt }
	code := authorizeCode(t, mux, testVerifier)
	p.now = func() time.Time { return issuedAt.Add(11 * time.Minute) }

	requireOAuthError(t, exchangeCode(mux, code, testRedirectURI, testVerifier), "invalid_grant")
	requireOAuthError(t, exchangeCode(mux, code, testRedirectURI, testVerifier), "invalid_grant")
}

func TestUnknownCodeAndUnsupportedGrantAreRejected(t *testing.T) {
	_, mux := testProvider(t)
	requireOAuthError(t, exchangeCode(mux, "unknown-code", testRedirectURI, testVerifier), "invalid_grant")

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/oauth/token", strings.NewReader("grant_type=client_credentials"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	mux.ServeHTTP(rec, req)
	requireOAuthError(t, rec, "unsupported_grant_type")
}

func TestE2EOAuthScriptHasFailureGates(t *testing.T) {
	const scriptPath = "../../scripts/e2e-oauth-flow.sh"
	data, err := os.ReadFile(scriptPath)
	if err != nil {
		t.Fatalf("read e2e OAuth script: %v", err)
	}
	script := string(data)
	for _, required := range []string{
		"set -euo pipefail",
		"code_challenge_method=S256",
		"authorization code replay returned HTTP",
		`assert text, "chat stream returned no text"`,
		`assert "END_TURN" in stop_reasons`,
	} {
		if !strings.Contains(script, required) {
			t.Errorf("e2e OAuth script missing failure gate %q", required)
		}
	}
	if strings.Contains(script, "get('access_token','')[:50]") {
		t.Error("e2e OAuth script truncates the access token before authenticated probes")
	}
	info, err := os.Stat(scriptPath)
	if err != nil {
		t.Fatalf("stat e2e OAuth script: %v", err)
	}
	if info.Mode()&0o111 == 0 {
		t.Error("e2e OAuth script is not executable")
	}
}
