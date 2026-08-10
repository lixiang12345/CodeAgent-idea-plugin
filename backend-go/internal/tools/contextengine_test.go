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

func TestSetActiveMapping(t *testing.T) {
	c := NewContextEngineClient()
	c.HostBase = "/Users/jiming"
	c.SetActive("/Users/jiming/FooProject")
	c.mu.Lock()
	name, root := c.activeName, c.activeLocalRoot
	c.mu.Unlock()
	if name != "FooProject" {
		t.Errorf("name = %q, want FooProject", name)
	}
	if root != "/host/FooProject" {
		t.Errorf("root = %q, want /host/FooProject", root)
	}
	// Nested project under home
	c.SetActive("/Users/jiming/work/Bar")
	c.mu.Lock()
	name, root = c.activeName, c.activeLocalRoot
	c.mu.Unlock()
	if name != "Bar" || root != "/host/work/Bar" {
		t.Errorf("got name=%q root=%q, want Bar /host/work/Bar", name, root)
	}
	// Non-home path stays absolute
	c.SetActive("/opt/svc")
	c.mu.Lock()
	root = c.activeLocalRoot
	c.mu.Unlock()
	if root != "/opt/svc" {
		t.Errorf("root = %q, want /opt/svc", root)
	}
}

func TestPerConversationWorkspace(t *testing.T) {
	e := New("")
	// Two conversations bound to two different projects.
	e.SetConversationWorkspace("convA", "/Users/jiming/CodeAgent-idea-plugin")
	e.SetConversationWorkspace("convB", "/Users/jiming/codeagentcli")

	// After binding convB last, a retrieval for convA must switch back to A.
	if got := e.workspaceForConversation("convA"); got != "/Users/jiming/CodeAgent-idea-plugin" {
		t.Errorf("convA workspace = %q", got)
	}
	if got := e.workspaceForConversation("convB"); got != "/Users/jiming/codeagentcli" {
		t.Errorf("convB workspace = %q", got)
	}
	e.Execute(&ToolCallRequest{Name: "codebase-retrieval", ConversationID: "convA"})
	e.mu.Lock()
	activeA := e.ContextEngine.activeName
	e.mu.Unlock()
	if activeA != "CodeAgent-idea-plugin" {
		t.Errorf("after convA retrieval active = %q, want CodeAgent-idea-plugin", activeA)
	}
	e.Execute(&ToolCallRequest{Name: "codebase-retrieval", ConversationID: "convB"})
	e.mu.Lock()
	activeB := e.ContextEngine.activeName
	e.mu.Unlock()
	if activeB != "codeagentcli" {
		t.Errorf("after convB retrieval active = %q, want codeagentcli", activeB)
	}
}
