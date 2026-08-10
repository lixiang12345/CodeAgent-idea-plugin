package surface

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestCompletionCallsGatewayAndReturnsProtoJSON(t *testing.T) {
	t.Setenv("MODEL_GATEWAY_API_KEY", "completion-key")

	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if got := r.Header.Get("Authorization"); got != "Bearer completion-key" {
			t.Errorf("authorization = %q", got)
		}
		var body struct {
			Model           string           `json:"model"`
			ReasoningEffort string           `json:"reasoning_effort"`
			MaxTokens       int              `json:"max_tokens"`
			Stream          bool             `json:"stream"`
			Messages        []map[string]any `json:"messages"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("decode gateway request: %v", err)
		}
		if body.Model != "completion-model" || body.ReasoningEffort != "low" || body.MaxTokens != 64 || body.Stream {
			t.Errorf("gateway request = %#v", body)
		}
		if len(body.Messages) != 2 {
			t.Errorf("messages = %d, want 2", len(body.Messages))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"return total"},"finish_reason":"stop"}]}`))
	}))
	defer gateway.Close()

	r := &Responder{GatewayURL: gateway.URL, GatewayModel: "completion-model"}
	response, handled, err := r.Handle("Completion", map[string]any{
		"prompt":     "func sum(a, b int) int {\n\t",
		"suffix":     "\n}",
		"path":       "sum.go",
		"lang":       "go",
		"max_tokens": float64(64),
	})
	if err != nil {
		t.Fatal(err)
	}
	if !handled {
		t.Fatal("Completion was not handled")
	}
	if calls.Load() != 1 {
		t.Fatalf("gateway calls = %d, want 1", calls.Load())
	}

	got, ok := response.(map[string]any)
	if !ok {
		t.Fatalf("response type = %T", response)
	}
	items, _ := got["completion_items"].([]any)
	if got["text"] != "return total" || len(items) != 1 {
		t.Fatalf("completion response = %#v", got)
	}
	item, _ := items[0].(map[string]any)
	if item["text"] != "return total" || item["skipped_suffix"] != "" || item["suffix_replacement_text"] != "" {
		t.Fatalf("completion item = %#v", item)
	}
	if _, exists := got["completionItems"]; exists {
		t.Fatalf("camelCase key is incompatible with released plugin: %#v", got)
	}
}

func TestCompletionFailureDegradesToEmptyProtoResponse(t *testing.T) {
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"error":{"message":"overloaded"}}`))
	}))
	defer gateway.Close()

	r := &Responder{GatewayURL: gateway.URL, GatewayModel: "completion-model"}
	response, handled, err := r.Handle("Completion", map[string]any{"prompt": "const answer = "})
	if err != nil {
		t.Fatal(err)
	}
	if !handled {
		t.Fatal("Completion was not handled")
	}
	got := response.(map[string]any)
	items, _ := got["completion_items"].([]any)
	unknown, _ := got["unknown_memory_names"].([]any)
	if got["text"] != "" || items == nil || len(items) != 0 || unknown == nil || got["checkpoint_not_found"] != false {
		t.Fatalf("empty completion response = %#v", got)
	}
}

func TestResolveCompletionsAcknowledgesTelemetry(t *testing.T) {
	r := &Responder{}
	response, handled, err := r.Handle("ResolveCompletionsRpc", map[string]any{
		"client_name": "intellij",
		"resolutions": []any{map[string]any{"request_id": "request-1", "accepted_idx": float64(0)}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !handled {
		t.Fatal("ResolveCompletionsRpc was not handled")
	}
	got, ok := response.(map[string]any)
	if !ok || len(got) != 0 {
		t.Fatalf("resolution response = %#v", response)
	}
}
