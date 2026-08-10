package tenant

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestEncodeChatResponseStopReasonError(t *testing.T) {
	got := encodeChatResponse(map[string]any{"stop_reason": "STOP_REASON_ERROR"})
	want := []byte{0x38, 0x07}
	if !bytes.Equal(got, want) {
		t.Fatalf("encoded stop reason = %x, want %x", got, want)
	}
}

func TestEncodeChatResponseTokenUsage(t *testing.T) {
	got := encodeChatResponse(map[string]any{"nodes": []any{map[string]any{
		"id":   float64(1),
		"type": "TOKEN_USAGE",
		"token_usage": map[string]any{
			"input_tokens":       float64(150),
			"output_tokens":      float64(12),
			"max_context_tokens": float64(200000),
		},
	}}})
	want := []byte{
		0x32, 0x0f,
		0x08, 0x01,
		0x10, 0x0a,
		0x42, 0x09,
		0x08, 0x96, 0x01,
		0x10, 0x0c,
		0x40, 0xc0, 0x9a, 0x0c,
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("encoded token usage = %x, want %x", got, want)
	}
}

func testServer() *httptest.Server {
	return httptest.NewServer(New("http://127.0.0.1:8787", "", "augment-local-code-1").Handler())
}

func successfulModelGateway(t *testing.T) *httptest.Server {
	t.Helper()
	t.Setenv("MODEL_GATEWAY_REASONING_EFFORT", "high")
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"hello from model\"}}]}\n\ndata: [DONE]\n\n"))
	}))
}

func post(t *testing.T, url, body string) *http.Response {
	t.Helper()
	req, err := http.NewRequest("POST", url, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func TestHealth(t *testing.T) {
	srv := testServer()
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("status = %d", resp.StatusCode)
	}
}

func TestContextEngineStatusIncludesPersistedConversationCount(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	server := New("http://127.0.0.1:8787", "", "augment-local-code-1")
	server.Store.CreateConversation("conversation-1", "workspace", "First", false)
	server.Store.CreateConversation("conversation-2", "workspace", "Second", false)
	srv := httptest.NewServer(server.Handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/contextengine/index-status")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var got struct {
		TotalThreads int `json:"totalThreads"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.TotalThreads != 2 {
		t.Fatalf("totalThreads = %d, want 2", got.TotalThreads)
	}
}

func TestContextEngineStatusUsesSyncedIDEThreadCount(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	server := New("http://127.0.0.1:8787", "", "augment-local-code-1")
	server.Store.CreateConversation("conversation-1", "workspace", "Backend conversation", false)
	srv := httptest.NewServer(server.Handler())
	defer srv.Close()

	syncResp := post(t, srv.URL+"/contextengine/thread-count", `{"totalThreads":7}`)
	defer syncResp.Body.Close()
	if syncResp.StatusCode != http.StatusOK {
		t.Fatalf("sync status = %d, want %d", syncResp.StatusCode, http.StatusOK)
	}

	statusResp, err := http.Get(srv.URL + "/contextengine/index-status")
	if err != nil {
		t.Fatal(err)
	}
	defer statusResp.Body.Close()
	var got struct {
		TotalThreads int `json:"totalThreads"`
	}
	if err := json.NewDecoder(statusResp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.TotalThreads != 7 {
		t.Fatalf("totalThreads = %d, want 7", got.TotalThreads)
	}
}

func TestContextEngineThreadCountRejectsNegativeCount(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	server := New("http://127.0.0.1:8787", "", "augment-local-code-1")
	server.Store.CreateConversation("conversation-1", "workspace", "Backend conversation", false)
	srv := httptest.NewServer(server.Handler())
	defer srv.Close()

	syncResp := post(t, srv.URL+"/contextengine/thread-count", `{"totalThreads":-1}`)
	defer syncResp.Body.Close()
	if syncResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("sync status = %d, want %d", syncResp.StatusCode, http.StatusBadRequest)
	}

	statusResp, err := http.Get(srv.URL + "/contextengine/index-status")
	if err != nil {
		t.Fatal(err)
	}
	defer statusResp.Body.Close()
	var got struct {
		TotalThreads int `json:"totalThreads"`
	}
	if err := json.NewDecoder(statusResp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.TotalThreads != 1 {
		t.Fatalf("totalThreads = %d, want persisted fallback 1", got.TotalThreads)
	}
}

func TestContextEngineThreadCountRejectsForeignOrigin(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	server := New("http://127.0.0.1:8787", "", "augment-local-code-1")
	srv := httptest.NewServer(server.Handler())
	defer srv.Close()

	req, err := http.NewRequest(http.MethodPost, srv.URL+"/contextengine/thread-count", strings.NewReader(`{"totalThreads":3}`))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", "https://example.com")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusForbidden)
	}
}

func TestContextEngineThreadCountRejectsExcessiveCount(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	srv := httptest.NewServer(New("http://127.0.0.1:8787", "", "augment-local-code-1").Handler())
	defer srv.Close()

	resp := post(t, srv.URL+"/contextengine/thread-count", `{"totalThreads":1000001}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusBadRequest)
	}
}

func TestContextEngineThreadCountIsScopedToWorkspace(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	server := New("http://127.0.0.1:8787", "", "augment-local-code-1")
	srv := httptest.NewServer(server.Handler())
	defer srv.Close()

	for _, body := range []string{
		`{"totalThreads":7,"workspaceRoot":"/workspace/project-a"}`,
		`{"totalThreads":2,"workspaceRoot":"/workspace/project-b"}`,
	} {
		resp := post(t, srv.URL+"/contextengine/thread-count", body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("sync status = %d, want %d", resp.StatusCode, http.StatusOK)
		}
	}

	server.activeWorkspaceRoot = "/workspace/project-a"
	statusResp, err := http.Get(srv.URL + "/contextengine/index-status")
	if err != nil {
		t.Fatal(err)
	}
	defer statusResp.Body.Close()
	var got struct {
		TotalThreads int `json:"totalThreads"`
	}
	if err := json.NewDecoder(statusResp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.TotalThreads != 7 {
		t.Fatalf("project-a totalThreads = %d, want 7", got.TotalThreads)
	}

	server.activeWorkspaceRoot = "/workspace/project-b"
	statusResp2, err := http.Get(srv.URL + "/contextengine/index-status")
	if err != nil {
		t.Fatal(err)
	}
	defer statusResp2.Body.Close()
	got = struct {
		TotalThreads int `json:"totalThreads"`
	}{}
	if err := json.NewDecoder(statusResp2.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.TotalThreads != 2 {
		t.Fatalf("project-b totalThreads = %d, want 2", got.TotalThreads)
	}
}

func TestContextEngineThreadCountUsesActiveWorkspaceWhenOmitted(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	server := New("http://127.0.0.1:8787", "", "augment-local-code-1")
	server.activeWorkspaceRoot = "/workspace/project-a"
	srv := httptest.NewServer(server.Handler())
	defer srv.Close()

	resp := post(t, srv.URL+"/contextengine/thread-count", `{"totalThreads":9}`)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("sync status = %d, want %d", resp.StatusCode, http.StatusOK)
	}
	server.activeWorkspaceRoot = "/workspace/project-b"
	statusResp, err := http.Get(srv.URL + "/contextengine/index-status")
	if err != nil {
		t.Fatal(err)
	}
	defer statusResp.Body.Close()
	var got struct {
		TotalThreads int `json:"totalThreads"`
	}
	if err := json.NewDecoder(statusResp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.TotalThreads != 0 {
		t.Fatalf("project-b totalThreads = %d, want empty workspace fallback 0", got.TotalThreads)
	}
}

func TestContextEngineThreadCountMigratesLegacySyncOnWorkspaceActivation(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	server := New("http://127.0.0.1:8787", "", "augment-local-code-1")
	srv := httptest.NewServer(server.Handler())
	defer srv.Close()

	resp := post(t, srv.URL+"/contextengine/thread-count", `{"totalThreads":9}`)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("sync status = %d, want %d", resp.StatusCode, http.StatusOK)
	}
	server.setActiveWorkspace("/workspace/project-a")
	statusResp, err := http.Get(srv.URL + "/contextengine/index-status")
	if err != nil {
		t.Fatal(err)
	}
	defer statusResp.Body.Close()
	var got struct {
		TotalThreads int `json:"totalThreads"`
	}
	if err := json.NewDecoder(statusResp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.TotalThreads != 9 {
		t.Fatalf("activated workspace totalThreads = %d, want migrated 9", got.TotalThreads)
	}
}

func TestGetModelsREST(t *testing.T) {
	srv := testServer()
	defer srv.Close()
	resp := post(t, srv.URL+"/api-client/get-models", `{}`)
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("status = %d", resp.StatusCode)
	}
	var d struct {
		DefaultModel string `json:"defaultModel"`
		Models       []struct {
			Name string `json:"name"`
		} `json:"models"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatal(err)
	}
	if d.DefaultModel == "" || len(d.Models) == 0 {
		t.Errorf("bad models response: %+v", d)
	}
}

func TestListRemoteToolsDoesNotDuplicateSidecarTools(t *testing.T) {
	srv := testServer()
	defer srv.Close()

	resp := post(t, srv.URL+"/api-client/agents/list-remote-tools", `{}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var got struct {
		Tools []any `json:"tools"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if len(got.Tools) != 0 {
		t.Fatalf("remote tools = %d, want 0; IDE-local tools belong to the sidecar", len(got.Tools))
	}
}

func TestChatInputCompletionUsesLowReasoningGatewayCall(t *testing.T) {
	t.Setenv("MODEL_GATEWAY_API_KEY", "test-key")

	var calls atomic.Int32
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if got := r.Header.Get("Authorization"); got != "Bearer test-key" {
			t.Errorf("authorization = %q", got)
		}
		var body struct {
			Model           string           `json:"model"`
			ReasoningEffort string           `json:"reasoning_effort"`
			Stream          bool             `json:"stream"`
			Messages        []map[string]any `json:"messages"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("decode gateway request: %v", err)
		}
		if body.Model != "completion-model" || body.ReasoningEffort != "low" || body.Stream {
			t.Errorf("gateway request = %#v", body)
		}
		if len(body.Messages) != 2 {
			t.Errorf("messages = %d, want 2", len(body.Messages))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"补充 ContextEngine 的错误恢复测试"},"finish_reason":"stop"}]}`))
	}))
	defer gateway.Close()

	srv := httptest.NewServer(New("http://127.0.0.1:8787", gateway.URL, "completion-model").Handler())
	defer srv.Close()
	resp := post(t, srv.URL+"/api-client/chat-input-completion", `{"prompt":"请继续完善","suffix":"。"}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var got struct {
		CompletionItems []struct {
			Text         string `json:"text"`
			FinishReason string `json:"finish_reason"`
		} `json:"completion_items"`
		UnknownMemoryNames []string `json:"unknown_memory_names"`
		CheckpointNotFound bool     `json:"checkpoint_not_found"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatalf("gateway calls = %d, want 1", calls.Load())
	}
	if len(got.CompletionItems) != 1 || got.CompletionItems[0].Text != "补充 ContextEngine 的错误恢复测试" {
		t.Fatalf("completion response = %#v", got)
	}
	if got.CompletionItems[0].FinishReason != "stop" {
		t.Fatalf("finish reason = %q", got.CompletionItems[0].FinishReason)
	}
	if got.UnknownMemoryNames == nil || got.CheckpointNotFound {
		t.Fatalf("completion metadata = %#v", got)
	}
}

func TestChatInputCompletionGatewayFailureDegradesToEmptySuggestion(t *testing.T) {
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte(`{"error":{"message":"overloaded"}}`))
	}))
	defer gateway.Close()

	srv := httptest.NewServer(New("http://127.0.0.1:8787", gateway.URL, "completion-model").Handler())
	defer srv.Close()
	resp := post(t, srv.URL+"/api-client/chat-input-completion", `{"prompt":"continue"}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want graceful 200", resp.StatusCode)
	}
	var got struct {
		CompletionItems    []any    `json:"completion_items"`
		UnknownMemoryNames []string `json:"unknown_memory_names"`
		CheckpointNotFound bool     `json:"checkpoint_not_found"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if len(got.CompletionItems) != 0 {
		t.Fatalf("completion items = %#v, want empty fallback", got.CompletionItems)
	}
	if got.CompletionItems == nil || got.UnknownMemoryNames == nil || got.CheckpointNotFound {
		t.Fatalf("completion fallback = %#v", got)
	}
}

func TestResolveChatInputCompletionAccepted(t *testing.T) {
	srv := testServer()
	defer srv.Close()
	resp := post(t, srv.URL+"/api-client/resolve-chat-input-completion", `{"requestId":"request-1","accepted":true}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
}

func TestRunRemoteToolUsesReleasedPluginContract(t *testing.T) {
	srv := testServer()
	defer srv.Close()

	resp := post(t, srv.URL+"/api-client/agents/run-remote-tool", `{"tool_name":"unsupported-cloud-tool","tool_input_json":"{}","tool_id":1}`)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var got struct {
		ToolOutput           string `json:"tool_output"`
		ToolResultMessage    string `json:"tool_result_message"`
		Status               string `json:"status"`
		CompressedFullOutput string `json:"compressed_full_output"`
		FullOutputSize       int64  `json:"full_output_size"`
		ContentNodes         []any  `json:"content_nodes"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.ToolOutput == "" || got.Status != "EXECUTION_ERROR" {
		t.Fatalf("remote tool response = %#v", got)
	}
	if got.ContentNodes == nil {
		t.Fatalf("content_nodes must be an empty array, got %#v", got.ContentNodes)
	}
}

func TestUnimplementedIsLoud(t *testing.T) {
	srv := testServer()
	defer srv.Close()
	resp := post(t, srv.URL+"/api-client/checkpoint-blobs", `{}`)
	defer resp.Body.Close()
	if resp.StatusCode != 501 {
		t.Fatalf("status = %d, want 501", resp.StatusCode)
	}
	var e struct {
		Code string `json:"code"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&e)
	if e.Code != "unimplemented" {
		t.Errorf("code = %q", e.Code)
	}
}

func TestChatStreamNDJSON(t *testing.T) {
	gateway := successfulModelGateway(t)
	defer gateway.Close()
	srv := httptest.NewServer(New("http://127.0.0.1:8787", gateway.URL, "test-model").Handler())
	defer srv.Close()
	resp := post(t, srv.URL+"/api-client/chat-stream", `{"message":"hi","conversation_id":"c1"}`)
	defer resp.Body.Close()
	sc := bufio.NewScanner(resp.Body)
	var sawThinking, sawFinished, sawText, sawEndTurn, sawError bool
	lines := 0
	for sc.Scan() && lines < 50 {
		line := sc.Text()
		if strings.TrimSpace(line) == "" {
			continue
		}
		lines++
		var evt map[string]any
		if err := json.Unmarshal([]byte(line), &evt); err != nil {
			t.Fatalf("bad stream line: %v", err)
		}
		if nodes, ok := evt["nodes"].([]any); ok {
			for _, n := range nodes {
				if nm, ok := n.(map[string]any); ok {
					switch nm["type"] {
					case "THINKING":
						sawThinking = true
					case "MAIN_TEXT_FINISHED":
						sawFinished = true
					}
				}
			}
		}
		if evt["stop_reason"] == "END_TURN" || evt["stop_reason"] == "STOP_REASON_ERROR" {
			sawEndTurn = evt["stop_reason"] == "END_TURN"
			sawError = evt["stop_reason"] == "STOP_REASON_ERROR"
		}
		if text, _ := evt["text"].(string); strings.Contains(text, "hello from model") {
			sawText = true
		}
	}
	if !sawThinking || !sawFinished || !sawText || !sawEndTurn || sawError {
		t.Errorf("stream events: thinking=%v finished=%v text=%v endTurn=%v error=%v (lines=%d)",
			sawThinking, sawFinished, sawText, sawEndTurn, sawError, lines)
	}
}

func TestChatStreamCreatesConversationForHomeStatus(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	gateway := successfulModelGateway(t)
	defer gateway.Close()
	server := New("http://127.0.0.1:8787", gateway.URL, "test-model")
	srv := httptest.NewServer(server.Handler())
	defer srv.Close()

	resp := post(t, srv.URL+"/api-client/chat-stream", `{"message":"hi","conversation_id":"home-thread"}`)
	defer resp.Body.Close()
	if _, err := io.Copy(io.Discard, resp.Body); err != nil {
		t.Fatal(err)
	}
	if got := len(server.Store.ListConversations("")); got != 1 {
		t.Fatalf("conversation count = %d, want 1", got)
	}
}

func TestChatStreamDoesNotLogRequestBody(t *testing.T) {
	gateway := successfulModelGateway(t)
	defer gateway.Close()

	var logs bytes.Buffer
	previousWriter := log.Writer()
	previousFlags := log.Flags()
	previousPrefix := log.Prefix()
	log.SetOutput(&logs)
	log.SetFlags(0)
	log.SetPrefix("")
	t.Cleanup(func() {
		log.SetOutput(previousWriter)
		log.SetFlags(previousFlags)
		log.SetPrefix(previousPrefix)
	})

	srv := httptest.NewServer(New("http://127.0.0.1:8787", gateway.URL, "test-model").Handler())
	defer srv.Close()
	const sensitiveMessage = "sensitive-chat-payload-must-not-reach-logs"
	resp := post(t, srv.URL+"/api-client/chat-stream", `{"message":"`+sensitiveMessage+`","conversation_id":"log-redaction"}`)
	defer resp.Body.Close()
	if _, err := io.Copy(io.Discard, resp.Body); err != nil {
		t.Fatalf("read chat stream: %v", err)
	}

	if strings.Contains(logs.String(), sensitiveMessage) {
		t.Fatalf("chat request body leaked into logs: %s", logs.String())
	}
	if !strings.Contains(logs.String(), "tenant: POST /api-client/chat-stream") {
		t.Fatalf("request metadata log missing; captured logs: %s", logs.String())
	}
}

func TestChatStreamSSE(t *testing.T) {
	gateway := successfulModelGateway(t)
	defer gateway.Close()
	srv := httptest.NewServer(New("http://127.0.0.1:8787", gateway.URL, "test-model").Handler())
	defer srv.Close()
	req, _ := http.NewRequest("POST", srv.URL+"/api-client/chat-stream", strings.NewReader(`{"message":"hi"}`))
	req.Header.Set("Accept", "text/event-stream")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Errorf("content-type = %q", ct)
	}
	sc := bufio.NewScanner(resp.Body)
	var dataFrames int
	for sc.Scan() && dataFrames < 30 {
		if strings.HasPrefix(sc.Text(), "data:") {
			dataFrames++
		}
	}
	if dataFrames < 3 {
		t.Errorf("only %d data frames", dataFrames)
	}
}

func TestConnectJSONUnaryAndUnimplemented(t *testing.T) {
	srv := testServer()
	defer srv.Close()
	req, _ := http.NewRequest("POST", srv.URL+"/augment.public_api.Augment/GetModels", strings.NewReader(`{}`))
	req.Header.Set("Content-Type", "application/connect+json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("unary status = %d", resp.StatusCode)
	}

	req2, _ := http.NewRequest("POST", srv.URL+"/augment.public_api.Augment/Edit", strings.NewReader(`{}`))
	req2.Header.Set("Content-Type", "application/connect+json")
	resp2, err := http.DefaultClient.Do(req2)
	if err != nil {
		t.Fatal(err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != 501 {
		t.Fatalf("unimplemented status = %d, want 501", resp2.StatusCode)
	}
}

func TestSubagentCodebaseRetrievalJSONAliasInheritsParentWorkspace(t *testing.T) {
	t.Setenv("STATE_FILE", t.TempDir()+"/state.json")
	workspace := t.TempDir()
	sentinel := filepath.Join(workspace, "subagent-context.txt")
	if err := os.WriteFile(sentinel, []byte("subagent-context-sentinel"), 0o600); err != nil {
		t.Fatal(err)
	}
	server := New("http://127.0.0.1:8787", "", "augment-local-code-1")
	server.ToolExecutor.ContextEngine.URL = ""
	server.ToolExecutor.SetConversationWorkspace("parent-conversation", workspace)
	srv := httptest.NewServer(server.Handler())
	defer srv.Close()

	req, err := http.NewRequest(http.MethodPost, srv.URL+"/augmentcode/api-client/agent-codebase-retrieval", strings.NewReader(`{"informationRequest":"subagent-context-sentinel","conversationId":"child-conversation","parentConversationId":"parent-conversation","chatHistory":[]}`))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/connect+json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("subagent retrieval status = %d, body = %s", resp.StatusCode, body)
	}
	var got struct {
		FormattedRetrieval string `json:"formatted_retrieval"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(got.FormattedRetrieval, "subagent-context-sentinel") {
		t.Fatalf("subagent retrieval did not inherit parent workspace: %q", got.FormattedRetrieval)
	}
}

func TestConnectProtoChatStreamFramed(t *testing.T) {
	srv := testServer()
	defer srv.Close()
	req, _ := http.NewRequest("POST", srv.URL+"/augment.public_api.Augment/ChatStream", strings.NewReader(`{"message":"hi"}`))
	req.Header.Set("Content-Type", "application/connect+proto")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body := make([]byte, 0, 4096)
	buf := make([]byte, 1024)
	for {
		n, err := resp.Body.Read(buf)
		body = append(body, buf[:n]...)
		if err != nil {
			break
		}
	}
	if len(body) < 5 {
		t.Fatalf("no framed data (%d bytes)", len(body))
	}
	if body[0] != 0x00 {
		t.Errorf("frame flag = %x, want 00", body[0])
	}
}

func TestGRPCChatStatus(t *testing.T) {
	srv := testServer()
	defer srv.Close()
	req, _ := http.NewRequest("POST", srv.URL+"/public_api.Augment/Chat", strings.NewReader(""))
	req.Header.Set("Content-Type", "application/grpc")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.Header.Get("Grpc-Status") != "0" {
		t.Errorf("Grpc-Status = %q, want 0", resp.Header.Get("Grpc-Status"))
	}
}

func TestDiscoveryTable(t *testing.T) {
	srv := testServer()
	defer srv.Close()
	resp := post(t, srv.URL+"/api-client/client-discovery", `{}`)
	defer resp.Body.Close()
	var d struct {
		Transports []struct {
			SupportedServices []string `json:"supported_services"`
			GRPC              struct {
				FullRPCURL string `json:"full_rpc_url"`
				Port       int    `json:"port"`
			} `json:"grpc"`
		} `json:"transports"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatal(err)
	}
	if len(d.Transports) == 0 {
		t.Fatal("no transports")
	}
	if len(d.Transports[0].SupportedServices) != 22 {
		t.Errorf("services = %d, want 22", len(d.Transports[0].SupportedServices))
	}
	if d.Transports[0].GRPC.Port != 8787 {
		t.Errorf("port = %d, want 8787", d.Transports[0].GRPC.Port)
	}
}
