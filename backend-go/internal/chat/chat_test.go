package chat

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"augment-local/internal/state"
)

func TestBuildMessagesFromIDEAssociatesToolResultFromNextExchange(t *testing.T) {
	t.Parallel()

	sim := &Simulator{}
	cfg := requestConfig{
		ChatHistory: []map[string]any{
			{
				"request_message": "inspect the repository",
				"response_nodes": []any{
					map[string]any{
						"tool_use": map[string]any{
							"tool_use_id": "call-1",
							"tool_name":   "codebase-retrieval",
							"input_json":  `{"information_request":"entry point"}`,
						},
					},
				},
			},
			{
				"request_nodes": []any{
					map[string]any{
						"tool_result_node": map[string]any{
							"tool_use_id": "call-1",
							"content":     "src/cli.tsx contains the entry point",
							"is_error":    false,
						},
					},
				},
			},
		},
	}

	messages := sim.buildMessagesFromIDE(cfg, "")
	toolMessage := findToolMessage(t, messages, "call-1")
	if got := str(toolMessage["content"]); got != "src/cli.tsx contains the entry point" {
		t.Fatalf("tool result content = %q, want the IDE result", got)
	}
}

func TestBuildMessagesFromIDEPreservesEmptyToolResult(t *testing.T) {
	t.Parallel()

	sim := &Simulator{}
	cfg := requestConfig{
		ChatHistory: []map[string]any{
			{
				"response_nodes": []any{
					map[string]any{
						"tool_use": map[string]any{
							"tool_use_id": "call-empty",
							"tool_name":   "codebase-retrieval",
							"input_json":  `{}`,
						},
					},
				},
			},
			{
				"request_nodes": []any{
					map[string]any{
						"tool_result_node": map[string]any{
							"tool_use_id": "call-empty",
							"content":     "",
							"is_error":    false,
						},
					},
				},
			},
		},
	}

	messages := sim.buildMessagesFromIDE(cfg, "")
	toolMessage := findToolMessage(t, messages, "call-empty")
	if got := str(toolMessage["content"]); got != "" {
		t.Fatalf("empty tool result was replaced with %q", got)
	}
}

func TestBuildMessagesFromIDEMatchesMultipleToolResultsByID(t *testing.T) {
	t.Parallel()

	sim := &Simulator{}
	cfg := requestConfig{
		ChatHistory: []map[string]any{
			{
				"request_message": "inspect files",
				"response_nodes": []any{
					toolUseNode("call-a", "view"),
					toolUseNode("call-b", "grep-search"),
					toolUseNode("call-missing", "view"),
				},
			},
			{
				"request_nodes": []any{
					map[string]any{"toolResultNode": map[string]any{
						"toolUseId": "call-b", "content": "grep result", "isError": false,
					}},
					map[string]any{"tool_result_node": map[string]any{
						"tool_use_id": "call-a", "content": "view result", "is_error": false,
					}},
				},
				"response_text": "finished analysis",
			},
		},
	}

	messages := sim.buildMessagesFromIDE(cfg, "")
	if got := str(findToolMessage(t, messages, "call-a")["content"]); got != "view result" {
		t.Fatalf("call-a content = %q", got)
	}
	if got := str(findToolMessage(t, messages, "call-b")["content"]); got != "grep result" {
		t.Fatalf("call-b content = %q", got)
	}
	if got := str(findToolMessage(t, messages, "call-missing")["content"]); got != "[tool executed by IDE]" {
		t.Fatalf("missing result placeholder = %q", got)
	}
	if got := str(messages[len(messages)-1]["content"]); got != "finished analysis" {
		t.Fatalf("result-only exchange response = %q", got)
	}
}

func TestParseToolResultNodeCamelCaseError(t *testing.T) {
	t.Parallel()

	result := parseToolResultNode(map[string]any{
		"toolResultNode": map[string]any{
			"toolUseId": "call-error",
			"content":   "permission denied",
			"isError":   true,
		},
	})
	if result == nil {
		t.Fatal("camelCase tool result was not parsed")
	}
	if str(result["tool_call_id"]) != "call-error" || !bo(result["is_error"]) {
		t.Fatalf("parsed result = %#v", result)
	}
}

func TestBuildMessagesFromIDEReplacesPlaceholderWithFreshResult(t *testing.T) {
	t.Parallel()

	sim := &Simulator{}
	cfg := requestConfig{
		IsContinuation: true,
		ChatHistory: []map[string]any{
			{
				"response_nodes": []any{toolUseNode("call-fresh", "view")},
			},
		},
		FreshToolResults: []map[string]any{
			{"tool_call_id": "call-fresh", "content": "fresh content", "is_error": false},
		},
	}

	messages := sim.buildMessagesFromIDE(cfg, "system prompt")
	var matching int
	for _, message := range messages {
		if message["role"] == "tool" && str(message["tool_call_id"]) == "call-fresh" {
			matching++
			if got := str(message["content"]); got != "fresh content" {
				t.Fatalf("fresh result content = %q", got)
			}
		}
	}
	if matching != 1 {
		t.Fatalf("matching tool messages = %d, want 1: %#v", matching, messages)
	}
}

func TestParseExchangeSupportsRequestAndResponseNodeVariants(t *testing.T) {
	t.Parallel()

	snakeText := parseExchange(map[string]any{
		"request_nodes": []any{map[string]any{"text_node": map[string]any{"content": "snake text"}}},
	})
	if snakeText.UserMsg != "snake text" {
		t.Fatalf("snake user message = %q", snakeText.UserMsg)
	}

	camelText := parseExchange(map[string]any{
		"request_nodes": []any{map[string]any{"textNode": map[string]any{"content": "camel text"}}},
		"response_nodes": []any{
			map[string]any{"tool_result_node": map[string]any{
				"tool_use_id": "legacy-result", "content": "legacy content", "is_error": false,
			}},
			map[string]any{"content": "inline assistant text"},
		},
	})
	if camelText.UserMsg != "camel text" || camelText.AssistantText != "inline assistant text" {
		t.Fatalf("camel exchange = %#v", camelText)
	}
	if len(camelText.ToolResults) != 1 || str(camelText.ToolResults[0]["tool_call_id"]) != "legacy-result" {
		t.Fatalf("legacy response tool results = %#v", camelText.ToolResults)
	}
}

func TestStreamReportsEmptyModelResponseAsError(t *testing.T) {
	t.Setenv("MODEL_GATEWAY_REASONING_EFFORT", "xhigh")

	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		var request struct {
			Stream bool `json:"stream"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Errorf("decode gateway request: %v", err)
		}
		if request.Stream {
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = w.Write([]byte("data: {\"choices\":[]}\n\ndata: [DONE]\n\n"))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[]}`))
	}))
	defer gateway.Close()

	sim := New(state.New(), gateway.URL, "test-model")
	var output bytes.Buffer
	err := sim.Stream(context.Background(), &output, FlowNDJSON, map[string]any{
		"message":         "continue after the tool result",
		"conversation_id": "conversation-1",
		"turn_id":         "turn-1",
		"model":           "test-model",
	})
	if err != nil {
		t.Fatalf("Stream returned error: %v", err)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("gateway calls = %d, want primary call plus one retry", got)
	}

	events := decodeEvents(t, output.String())
	if !eventsContainText(events, "模型网关返回空响应") {
		t.Fatalf("stream did not report the empty gateway response: %s", output.String())
	}
	if got := countStopReason(events, "STOP_REASON_ERROR"); got != 1 {
		t.Fatalf("STOP_REASON_ERROR count = %d, want 1: %s", got, output.String())
	}
	if got := countStopReason(events, "END_TURN"); got != 0 {
		t.Fatalf("END_TURN count = %d, want 0 for a failed model call: %s", got, output.String())
	}
	if strings.Contains(output.String(), "Done.") {
		t.Fatalf("empty model response was presented as success: %s", output.String())
	}
}

func TestStreamRejectsEmptyResponseWithoutRetry(t *testing.T) {
	t.Setenv("MODEL_GATEWAY_REASONING_EFFORT", "high")

	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte("data: {\"choices\":[]}\n\ndata: [DONE]\n\n"))
	}))
	defer gateway.Close()

	sim := New(state.New(), gateway.URL, "test-model")
	var output bytes.Buffer
	err := sim.Stream(context.Background(), &output, FlowNDJSON, map[string]any{
		"message": "answer the question",
		"model":   "test-model",
	})
	if err != nil {
		t.Fatalf("Stream returned error: %v", err)
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("gateway calls = %d, want 1 for non-xhigh reasoning", got)
	}
	events := decodeEvents(t, output.String())
	if !eventsContainText(events, "模型网关返回空响应") {
		t.Fatalf("stream did not report empty response: %s", output.String())
	}
	if got := countStopReason(events, "STOP_REASON_ERROR"); got != 1 {
		t.Fatalf("STOP_REASON_ERROR count = %d, want 1", got)
	}
}

func TestCallOpenAIRetryPreservesToolCalls(t *testing.T) {
	t.Setenv("MODEL_GATEWAY_REASONING_EFFORT", "xhigh")

	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		call := calls.Add(1)
		if call == 1 {
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = w.Write([]byte("data: {\"choices\":[]}\n\ndata: [DONE]\n\n"))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"choices":[{
				"message":{
					"content":"",
					"tool_calls":[{
						"id":"call-retry",
						"type":"function",
						"function":{"name":"view","arguments":"{\"path\":\"README.md\"}"}
					}]
				}
			}]
		}`))
	}))
	defer gateway.Close()

	sim := &Simulator{HTTPClient: gateway.Client()}
	response, err := sim.callOpenAI(
		context.Background(), gateway.URL, "test-model", nil, nil,
		func(map[string]any) error { return nil },
	)
	if err != nil {
		t.Fatalf("callOpenAI returned error: %v", err)
	}
	if len(response.ToolCalls) != 1 {
		t.Fatalf("retry tool calls = %#v, want one", response.ToolCalls)
	}
	if got := response.ToolCalls[0]; got.ID != "call-retry" || got.Name != "view" {
		t.Fatalf("retry tool call = %#v", got)
	}
}

func TestCallOpenAIStreamsContentAndToolCall(t *testing.T) {
	t.Setenv("MODEL_GATEWAY_REASONING_EFFORT", "high")

	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte(
			"data: {\"choices\":[{\"delta\":{\"content\":\"checking \"}}]}\n\n" +
				"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-stream\",\"function\":{\"name\":\"view\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}\n\n" +
				"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"README.md\\\"}\"}}]}}]}\n\n" +
				"data: [DONE]\n\n",
		))
	}))
	defer gateway.Close()

	var streamed strings.Builder
	sim := &Simulator{HTTPClient: gateway.Client()}
	response, err := sim.callOpenAI(
		context.Background(), gateway.URL, "test-model", nil, nil,
		func(event map[string]any) error {
			streamed.WriteString(str(event["text"]))
			return nil
		},
	)
	if err != nil {
		t.Fatalf("callOpenAI returned error: %v", err)
	}
	if response.Content != "checking " || streamed.String() != "checking " {
		t.Fatalf("content response=%q streamed=%q", response.Content, streamed.String())
	}
	if len(response.ToolCalls) != 1 {
		t.Fatalf("tool calls = %#v", response.ToolCalls)
	}
	if got := response.ToolCalls[0]; got.ID != "call-stream" || got.Name != "view" || got.Arguments != `{"path":"README.md"}` {
		t.Fatalf("streamed tool call = %#v", got)
	}
}

func TestCallOpenAIRetryStreamsRecoveredText(t *testing.T) {
	t.Setenv("MODEL_GATEWAY_REASONING_EFFORT", "xhigh")

	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if calls.Add(1) == 1 {
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = w.Write([]byte("data: {\"choices\":[]}\n\ndata: [DONE]\n\n"))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"recovered answer"}}]}`))
	}))
	defer gateway.Close()

	var streamed strings.Builder
	sim := &Simulator{HTTPClient: gateway.Client()}
	response, err := sim.callOpenAI(
		context.Background(), gateway.URL, "test-model", nil, nil,
		func(event map[string]any) error {
			streamed.WriteString(str(event["text"]))
			return nil
		},
	)
	if err != nil {
		t.Fatalf("callOpenAI returned error: %v", err)
	}
	if response.Content != "recovered answer" || !response.Streamed {
		t.Fatalf("retry response = %#v", response)
	}
	if streamed.String() != "recovered answer" {
		t.Fatalf("streamed retry text = %q", streamed.String())
	}
}

func TestRetryOpenAILowReportsHTTPError(t *testing.T) {
	t.Parallel()

	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "rate limited", http.StatusTooManyRequests)
	}))
	defer gateway.Close()

	sim := &Simulator{HTTPClient: gateway.Client()}
	_, err := sim.retryOpenAILow(context.Background(), gateway.URL, "test-model", nil, nil)
	if err == nil || !strings.Contains(err.Error(), "status 429") {
		t.Fatalf("retry error = %v, want status 429", err)
	}
}

func TestCallOpenAIReportsHTTPError(t *testing.T) {
	t.Parallel()

	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte(`{"error":{"message":"upstream unavailable"}}`))
	}))
	defer gateway.Close()

	sim := &Simulator{HTTPClient: gateway.Client()}
	_, err := sim.callOpenAI(
		context.Background(), gateway.URL, "test-model", nil, nil,
		func(map[string]any) error { return nil },
	)
	if err == nil || !strings.Contains(err.Error(), "status 502: upstream unavailable") {
		t.Fatalf("callOpenAI error = %v", err)
	}
}

func TestRetryOpenAILowReportsDecodeError(t *testing.T) {
	t.Parallel()

	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":`))
	}))
	defer gateway.Close()

	sim := &Simulator{HTTPClient: gateway.Client()}
	_, err := sim.retryOpenAILow(context.Background(), gateway.URL, "test-model", nil, nil)
	if err == nil {
		t.Fatal("malformed retry response was accepted")
	}
}

func TestCallOpenAIReportsJSONErrorEnvelope(t *testing.T) {
	t.Setenv("MODEL_GATEWAY_REASONING_EFFORT", "high")

	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"error":{"message":"servers overloaded","type":"upstream_error"},"type":"error"}`))
	}))
	defer gateway.Close()

	sim := &Simulator{HTTPClient: gateway.Client()}
	_, err := sim.callOpenAI(
		context.Background(), gateway.URL, "test-model", nil, nil,
		func(map[string]any) error { return nil },
	)
	if err == nil || !strings.Contains(err.Error(), "servers overloaded") {
		t.Fatalf("callOpenAI error = %v", err)
	}
}

func TestRetryOpenAILowReportsErrorEnvelope(t *testing.T) {
	t.Parallel()

	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"error":{"message":"servers overloaded","type":"upstream_error"},"type":"error"}`))
	}))
	defer gateway.Close()

	sim := &Simulator{HTTPClient: gateway.Client()}
	_, err := sim.retryOpenAILow(context.Background(), gateway.URL, "test-model", nil, nil)
	if err == nil || !strings.Contains(err.Error(), "servers overloaded") {
		t.Fatalf("retry error = %v", err)
	}
}

func findToolMessage(t *testing.T, messages []map[string]any, toolCallID string) map[string]any {
	t.Helper()
	for _, message := range messages {
		if message["role"] == "tool" && str(message["tool_call_id"]) == toolCallID {
			return message
		}
	}
	t.Fatalf("tool message %q not found in %#v", toolCallID, messages)
	return nil
}

func toolUseNode(id, name string) map[string]any {
	return map[string]any{
		"tool_use": map[string]any{
			"tool_use_id": id,
			"tool_name":   name,
			"input_json":  `{}`,
		},
	}
}

func decodeEvents(t *testing.T, output string) []map[string]any {
	t.Helper()
	var events []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(output), "\n") {
		if line == "" {
			continue
		}
		var event map[string]any
		if err := json.Unmarshal([]byte(line), &event); err != nil {
			t.Fatalf("decode stream event %q: %v", line, err)
		}
		events = append(events, event)
	}
	return events
}

func eventsContainText(events []map[string]any, want string) bool {
	var text strings.Builder
	for _, event := range events {
		text.WriteString(str(event["text"]))
	}
	return strings.Contains(text.String(), want)
}

func countStopReason(events []map[string]any, want string) int {
	count := 0
	for _, event := range events {
		if str(event["stop_reason"]) == want {
			count++
		}
	}
	return count
}
