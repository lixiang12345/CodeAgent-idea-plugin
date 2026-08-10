package main

import (
	"os"
	"strings"
	"testing"
)

func TestComposeBindsLocalAPIsToLoopback(t *testing.T) {
	data, err := os.ReadFile("../../compose.yaml")
	if err != nil {
		t.Fatalf("read compose.yaml: %v", err)
	}
	compose := string(data)

	for _, binding := range []string{
		`- "127.0.0.1:8445:8445"`,
		`- "127.0.0.1:8787:8787"`,
	} {
		if !strings.Contains(compose, binding) {
			t.Errorf("compose.yaml missing loopback-only port binding %s", binding)
		}
	}
	for _, publicBinding := range []string{
		`- "8445:8445"`,
		`- "8787:8787"`,
	} {
		if strings.Contains(compose, publicBinding) {
			t.Errorf("compose.yaml exposes local API on all interfaces: %s", publicBinding)
		}
	}
}
