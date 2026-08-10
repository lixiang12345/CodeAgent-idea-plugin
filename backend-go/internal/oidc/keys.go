package oidc

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"fmt"
)

// SHA256B64 returns the base64url (no padding) SHA-256 of s — the S256 PKCE
// transformation, exposed for tests.
func SHA256B64(s string) string {
	sum := sha256.Sum256([]byte(s))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// x509ParsePKCS1 decodes an RSA PRIVATE KEY block.
func x509ParsePKCS1(der []byte) (*rsa.PrivateKey, error) {
	k, err := x509.ParsePKCS1PrivateKey(der)
	if err != nil {
		return nil, fmt.Errorf("pkcs1: %w", err)
	}
	return k, nil
}

// x509ParsePKCS8 decodes a PKCS#8 PRIVATE KEY block.
func x509ParsePKCS8(der []byte) (*rsa.PrivateKey, error) {
	k, err := x509.ParsePKCS8PrivateKey(der)
	if err != nil {
		return nil, fmt.Errorf("pkcs8: %w", err)
	}
	rsaKey, ok := k.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("not an RSA key")
	}
	return rsaKey, nil
}

// EncodePrivateKeyPEM serializes an RSA key as PKCS#8 PEM (for diagnostics /
// pre-generated keys).
func EncodePrivateKeyPEM(k *rsa.PrivateKey) string {
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		return ""
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))
}

// randomToken returns a URL-safe random token of n bytes.
func randomToken(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "0"
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
