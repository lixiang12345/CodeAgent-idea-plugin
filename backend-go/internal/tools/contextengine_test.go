package tools

import (
	"encoding/json"
	"os"
	"testing"
	"time"
)

// TestCodebaseRetrievalProxy verifies the codebase-retrieval tool proxies to
// ContextEngine when configured (needs the compose stack running).
func TestCodebaseRetrievalProxy(t *testing.T) {
	key := os.Getenv("CONTEXTENGINE_HTTP_API_KEY")
	if key == "" {
		t.Skip("CONTEXTENGINE_HTTP_API_KEY not set; skipping integration test")
	}
	os.Setenv("CONTEXTENGINE_URL", "http://127.0.0.1:8790")
	os.Setenv("CONTEXTENGINE_API_KEY", key)
	os.Setenv("CONTEXTENGINE_WORKSPACE", "local")
	os.Setenv("CONTEXTENGINE_LOCAL_ROOT", "/host/CodeAgent-idea-plugin")

	e := New("")
	input, _ := json.Marshal(map[string]any{
		"information_request": "codebase-retrieval tool implementation",
		"top_k":               3,
	})

	// The first call may kick off indexing; poll until the evidence pack is
	// real (index done) or we run out of time.
	deadline := time.Now().Add(120 * time.Second)
	var last *ToolCallResponse
	for time.Now().Before(deadline) {
		resp := e.Execute(&ToolCallRequest{Name: "codebase-retrieval", Input: input})
		last = resp
		if resp != nil && !resp.IsError && len(resp.Text) > 500 {
			t.Logf("OK: got %d chars:\n%.400s", len(resp.Text), resp.Text)
			return
		}
		time.Sleep(5 * time.Second)
	}
	msg := ""
	if last != nil {
		msg = last.Text
	}
	t.Fatalf("codebase-retrieval never returned a real evidence pack within 120s; last resp: %s", msg)
}
