package tenant

import (
	"bufio"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestEncodeChatResponseStopReasonError(t *testing.T) {
	got := encodeChatResponse(map[string]any{"stop_reason": "STOP_REASON_ERROR"})
	want := []byte{0x38, 0x07}
	if !bytes.Equal(got, want) {
		t.Fatalf("encoded stop reason = %x, want %x", got, want)
	}
}

func testServer() *httptest.Server {
	return httptest.NewServer(New("http://127.0.0.1:8787", "", "augment-local-code-1").Handler())
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
	srv := testServer()
	defer srv.Close()
	resp := post(t, srv.URL+"/api-client/chat-stream", `{"message":"hi","conversation_id":"c1"}`)
	defer resp.Body.Close()
	sc := bufio.NewScanner(resp.Body)
	var sawThinking, sawFinished, sawStop bool
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
			sawStop = true
		}
	}
	if !sawThinking || !sawFinished || !sawStop {
		t.Errorf("stream missing events: thinking=%v finished=%v stop=%v (lines=%d)", sawThinking, sawFinished, sawStop, lines)
	}
}

func TestChatStreamSSE(t *testing.T) {
	srv := testServer()
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
