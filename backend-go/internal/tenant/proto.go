package tenant

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"

	"augment-local/internal/chat"
	"augment-local/internal/surface"
	"augment-local/internal/tools"
)

// handleRPC dispatches a connect/gRPC/gRPC-web style call
// /<package.Service>/<Method> by its Content-Type. The sidecar's connect-js
// client sends application/connect+json or application/connect+proto; the JVM
// sidecar bridge can also arrive over application/grpc*.
func (s *Server) handleRPC(w http.ResponseWriter, r *http.Request, svc, method string) {
	// Normalize service: accept both augment.public_api.Augment and
	// public_api.Augment.
	method = strings.TrimSuffix(strings.TrimPrefix(method, "/"), "/")
	ct := r.Header.Get("Content-Type")
	// The sidecar's subagent retrieval client uses this host-facing alias,
	// while the public REST client uses /agents/codebase-retrieval. Keep both
	// paths on the same local ContextEngine implementation.
	if svc == "augmentcode" && method == "api-client/agent-codebase-retrieval" {
		if strings.HasPrefix(ct, "application/connect+json") || strings.HasPrefix(ct, "application/json") || ct == "" {
			s.handleAgentCodebaseRetrievalJSON(w, r)
			return
		}
	}
	switch {
	case strings.HasPrefix(ct, "application/connect+json"):
		s.handleConnectJSON(w, r, method)
	case strings.HasPrefix(ct, "application/connect+proto"):
		s.handleConnectProto(w, r, method)
	case strings.HasPrefix(ct, "application/grpc-web+proto"), strings.HasPrefix(ct, "application/grpc-web+json"):
		s.handleGRPCWeb(w, r, method)
	case strings.HasPrefix(ct, "application/grpc"):
		s.handleGRPC(w, r, method)
	default:
		// No protocol content type (e.g. application/json to a connect path):
		// treat as connect JSON, which is what connect-js sends for the REST
		// gateway too.
		s.handleConnectJSON(w, r, method)
	}
}

func (s *Server) handleAgentCodebaseRetrievalJSON(w http.ResponseWriter, r *http.Request) {
	req := decodeBody(r)
	// The host RPC uses proto JSON lowerCamelCase fields. Normalize them to
	// the snake_case shape consumed by the existing retrieval responder.
	normalized := map[string]any{
		"information_request":    firstValue(req, "informationRequest", "information_request"),
		"conversation_id":        firstValue(req, "conversationId", "conversation_id"),
		"parent_conversation_id": firstValue(req, "parentConversationId", "parent_conversation_id"),
		"root_conversation_id":   firstValue(req, "rootConversationId", "root_conversation_id"),
		"dialog":                 firstValue(req, "chatHistory", "dialog"),
	}
	if value := firstValue(req, "maxOutputLength", "max_output_length"); value != nil {
		normalized["max_output_length"] = value
	}
	resp, handled, err := s.Responder.Handle("CodebaseRetrieval", normalized)
	if err != nil {
		connectError(w, "internal", err.Error(), http.StatusInternalServerError)
		return
	}
	if !handled {
		connectError(w, "unimplemented", "surface not implemented: CodebaseRetrieval", http.StatusNotImplemented)
		return
	}
	w.Header().Set("Content-Type", "application/connect+json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(resp)
}

func firstValue(values map[string]any, names ...string) any {
	for _, name := range names {
		if value, ok := values[name]; ok {
			return value
		}
	}
	return nil
}

// handleConnectJSON serves connect+json: unary responses as a single JSON
// object, streaming responses (ChatStream) as newline-delimited JSON.
func (s *Server) handleConnectJSON(w http.ResponseWriter, r *http.Request, method string) {
	// augmentcode/tools/call — server-side tool execution forwarded by the IDE.
	if method == "tools/call" {
		s.handleToolsCall(w, r)
		return
	}
	if surface.ImplementedStreams[method] {
		req := decodeBody(r)
		w.Header().Set("Content-Type", "application/connect+json")
		w.WriteHeader(http.StatusOK)
		if err := s.Chat.Stream(r.Context(), w, chat.FlowNDJSON, req); err != nil {
			log.Printf("tenant: connect stream error: %v", err)
		}
		return
	}
	req := decodeBody(r)
	resp, handled, err := s.Responder.Handle(method, req)
	if err != nil {
		connectError(w, "internal", err.Error(), http.StatusInternalServerError)
		return
	}
	if !handled {
		log.Printf("tenant: connect %s -> unimplemented", method)
		connectError(w, "unimplemented", fmt.Sprintf("surface not implemented: %s", method), http.StatusNotImplemented)
		return
	}
	w.Header().Set("Content-Type", "application/connect+json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(resp)
}

// handleConnectProto serves connect+proto. The ChatStream simulator also works
// in this codec (hand-encoded ChatResponse messages); other methods answer a
// proper connect Unimplemented error because we deliberately do not ship the
// generated proto marshallers (JSON REST is the primary surface).
func (s *Server) handleConnectProto(w http.ResponseWriter, r *http.Request, method string) {
	if surface.ImplementedStreams[method] {
		req := decodeBody(r)
		w.Header().Set("Content-Type", "application/connect+proto")
		w.WriteHeader(http.StatusOK)
		fw := &frameWriter{w: w}
		if err := s.Chat.Stream(r.Context(), fw, chat.FlowProto, req); err != nil {
			log.Printf("tenant: connect+proto stream error: %v", err)
		}
		return
	}
	log.Printf("tenant: connect+proto %s -> unimplemented (JSON primary)", method)
	connectError(w, "unimplemented", fmt.Sprintf("surface not implemented over connect+proto: %s (use JSON REST)", method), http.StatusNotImplemented)
}

// handleGRPC serves gRPC over HTTP/2 (h2c). Real protobuf responses are wired
// for the health service and for Chat (ChatResponse); every other RPC answers a
// trailers-only Unimplemented because we deliberately do not ship the generated
// marshallers — JSON REST is the primary surface.
func (s *Server) handleGRPC(w http.ResponseWriter, r *http.Request, method string) {
	w.Header().Set("Content-Type", "application/grpc")
	w.Header().Set("Trailer", "Grpc-Status, Grpc-Message")
	if method == "Check" {
		w.Header().Set("Grpc-Status", "0")
		w.Header().Set("Grpc-Message", "")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(frameMessage([]byte{0x08, 0x01})) // HealthCheckResponse{status: SERVING}
		return
	}
	if method == "Chat" {
		req := decodeGRPCRequest(r)
		resp, handled, err := s.Responder.Handle("Chat", req)
		if err != nil {
			w.Header().Set("Grpc-Status", "13")
			w.WriteHeader(http.StatusOK)
			return
		}
		if !handled {
			w.Header().Set("Grpc-Status", "12")
			w.WriteHeader(http.StatusOK)
			return
		}
		evt, _ := resp.(map[string]any)
		w.Header().Set("Grpc-Status", "0")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(frameMessage(encodeChatResponse(evt)))
		return
	}
	w.Header().Set("Grpc-Status", "12")
	w.Header().Set("Grpc-Message", url.PathEscape(fmt.Sprintf("surface not implemented: %s", method)))
	w.WriteHeader(http.StatusOK)
}

// handleGRPCWeb serves grpc-web. Status travels in a trailer frame in the body.
func (s *Server) handleGRPCWeb(w http.ResponseWriter, r *http.Request, method string) {
	if method == "Check" {
		w.Header().Set("Content-Type", "application/grpc-web+proto")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(frameMessage([]byte{0x08, 0x01}))
		_, _ = w.Write(grpcWebTrailerFrame(0, ""))
		return
	}
	w.Header().Set("Content-Type", "application/grpc-web+proto")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(grpcWebTrailerFrame(12, fmt.Sprintf("surface not implemented: %s", method)))
}

func (s *Server) handleGRPCHealth(w http.ResponseWriter, r *http.Request) {
	ct := r.Header.Get("Content-Type")
	switch {
	case strings.HasPrefix(ct, "application/connect+json"):
		w.Header().Set("Content-Type", "application/connect+json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{"status": "SERVING"})
	case strings.HasPrefix(ct, "application/connect+proto"):
		w.Header().Set("Content-Type", "application/connect+proto")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(frameMessage([]byte{0x08, 0x01}))
	default:
		s.handleGRPC(w, r, "Check")
	}
}

// connectError writes a connect-protocol error envelope.
func connectError(w http.ResponseWriter, code, message string, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Grpc-Status", fmt.Sprint(connectCodeToGRPC(code)))
	w.Header().Set("Connect-Protocol-Version", "1")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"code":    code,
		"message": message,
	})
}

func connectCodeToGRPC(code string) int {
	switch code {
	case "unimplemented":
		return 12
	case "not_found":
		return 5
	case "internal":
		return 13
	case "invalid_argument":
		return 3
	case "unauthenticated":
		return 16
	case "permission_denied":
		return 7
	case "unavailable":
		return 14
	case "failed_precondition":
		return 9
	default:
		return 2
	}
}

// decodeGRPCRequest reads a length-framed protobuf request body and extracts
// the ChatRequest string fields the simulator cares about (message=6,
// conversation_id=32, turn_id=38). The walker skips anything else, so the
// decode stays schema-light while remaining correct for the fields we use.
func decodeGRPCRequest(r *http.Request) map[string]any {
	out := map[string]any{}
	body, err := io.ReadAll(r.Body)
	if err != nil || len(body) < 5 {
		return out
	}
	// Skip the 5-byte gRPC frame header (flag + big-endian length).
	msg := body[5:]
	i := 0
	fields := map[int]any{}
	for i < len(msg) {
		tag, n := readVarint(msg, i)
		if n == 0 {
			break
		}
		i = n
		field, wt := tag>>3, int(tag&7)
		switch wt {
		case 0:
			v, n := readVarint(msg, i)
			if n == 0 {
				i = len(msg)
				break
			}
			fields[int(field)] = v
			i = n
		case 2:
			l, n := readVarint(msg, i)
			if n == 0 || n+int(l) > len(msg) {
				i = len(msg)
				break
			}
			fields[int(field)] = msg[n : n+int(l)]
			i = n + int(l)
		default:
			// fixed32/fixed64 or unknown: skip 4/8 bytes by wire type.
			skip := map[int]int{1: 8, 5: 4, 3: 4, 4: 8}[wt]
			if skip == 0 || i+skip > len(msg) {
				i = len(msg)
				break
			}
			i += skip
		}
	}
	if v, ok := fields[6].([]byte); ok {
		out["message"] = string(v)
	}
	if v, ok := fields[32].([]byte); ok {
		out["conversation_id"] = string(v)
	}
	if v, ok := fields[38].([]byte); ok {
		out["turn_id"] = string(v)
	}
	return out
}

// readVarint decodes a base-128 varint starting at i; returns (value, nextIdx).
func readVarint(b []byte, i int) (uint64, int) {
	var v uint64
	var shift uint
	for {
		if i >= len(b) {
			return 0, 0
		}
		x := b[i]
		i++
		v |= uint64(x&0x7f) << shift
		if x&0x80 == 0 {
			return v, i
		}
		shift += 7
		if shift >= 64 {
			return 0, 0
		}
	}
}

// frameMessage prefixes a protobuf payload with the standard 5-byte frame
// header (flag 0 + big-endian length).
func frameMessage(payload []byte) []byte {
	out := make([]byte, 5+len(payload))
	binary.BigEndian.PutUint32(out[1:5], uint32(len(payload)))
	copy(out[5:], payload)
	return out
}

// grpcWebTrailerFrame builds the grpc-web trailer frame: 0x80 flag + length +
// HTTP/1 style "grpc-status: N\r\ngrpc-message: M\r\n".
func grpcWebTrailerFrame(status int, message string) []byte {
	body := fmt.Sprintf("grpc-status: %d\r\ngrpc-message: %s\r\n", status, url.PathEscape(message))
	out := make([]byte, 5+len(body))
	out[0] = 0x80
	binary.BigEndian.PutUint32(out[1:5], uint32(len(body)))
	copy(out[5:], body)
	return out
}

// frameWriter adapts an io.Writer into the chat.FlowProto stream: it wraps
// every ChatResponse JSON event with a proto frame. The simulator calls Flush
// after each event; for the framed codec a no-op flush is fine.
type frameWriter struct {
	w http.ResponseWriter
}

func (f *frameWriter) Write(p []byte) (int, error) {
	var evt map[string]any
	if err := json.Unmarshal(p, &evt); err != nil {
		return 0, err
	}
	_, err := f.w.Write(frameMessage(encodeChatResponse(evt)))
	return len(p), err
}

func (f *frameWriter) Flush() error {
	if fl, ok := f.w.(http.Flusher); ok {
		fl.Flush()
	}
	return nil
}

// --- Minimal protobuf wire encoder for ChatResponse (public_api) ----------

// encodeChatResponse converts a stream event map (as produced by the JSON
// simulator) into ChatResponse protobuf bytes. Fields:
//
//	message ChatResponse { string text=1; repeated ChatResultNode nodes=6; ChatStopReason stop_reason=7; }
//	message ChatResultNode { int32 id=1; ChatResultNodeType type=2; string content=3; ChatResultToolUse tool_use=4; uint64 timestamp_ms=10; }
//	message ChatResultToolUse { string tool_use_id=1; string tool_name=2; string input_json=3; bool is_partial=4; }
func encodeChatResponse(evt map[string]any) []byte {
	var out bytes.Buffer
	if text, ok := evt["text"].(string); ok && text != "" {
		writeString(&out, 1, text)
	}
	if nodes, ok := evt["nodes"].([]any); ok {
		for _, n := range nodes {
			if nm, ok := n.(map[string]any); ok {
				sub := encodeChatResultNode(nm)
				writeMessage(&out, 6, sub)
			}
		}
	}
	if stop, ok := evt["stop_reason"].(string); ok {
		stopValues := map[string]uint64{
			"END_TURN":                1,
			"MAX_TOKENS":              2,
			"TOOL_USE_REQUESTED":      3,
			"SAFETY":                  4,
			"RECITATION":              5,
			"MALFORMED_FUNCTION_CALL": 6,
			"STOP_REASON_ERROR":       7,
		}
		if value, found := stopValues[stop]; found {
			writeVarint(&out, 7, value)
		}
	}
	return out.Bytes()
}

func encodeChatResultNode(n map[string]any) []byte {
	var out bytes.Buffer
	if id, ok := n["id"].(float64); ok {
		writeVarint(&out, 1, uint64(int64(id)))
	}
	if typ, ok := n["type"].(string); ok {
		writeVarint(&out, 2, uint64(nodeTypeNumber(typ)))
	}
	if content, ok := n["content"].(string); ok && content != "" {
		writeString(&out, 3, content)
	}
	if tu, ok := n["tool_use"].(map[string]any); ok {
		writeMessage(&out, 4, encodeToolUse(tu))
	}
	if ts, ok := n["timestamp_ms"].(float64); ok {
		writeVarint(&out, 10, uint64(ts))
	}
	return out.Bytes()
}

func encodeToolUse(tu map[string]any) []byte {
	var out bytes.Buffer
	if id, ok := tu["tool_use_id"].(string); ok {
		writeString(&out, 1, id)
	}
	if name, ok := tu["tool_name"].(string); ok {
		writeString(&out, 2, name)
	}
	if input, ok := tu["input_json"].(string); ok {
		writeString(&out, 3, input)
	}
	if partial, ok := tu["is_partial"].(bool); ok {
		writeVarint(&out, 4, boolToUint(partial))
	}
	return out.Bytes()
}

func nodeTypeNumber(name string) int {
	switch name {
	case "RAW_RESPONSE":
		return 0
	case "SUGGESTED_QUESTIONS":
		return 1
	case "MAIN_TEXT_FINISHED":
		return 2
	case "WORKSPACE_FILE_CHUNKS":
		return 3
	case "RELEVANT_SOURCES":
		return 4
	case "TOOL_USE":
		return 5
	case "TOOL_USE_START":
		return 7
	case "THINKING":
		return 8
	case "BILLING_METADATA":
		return 9
	case "TOKEN_USAGE":
		return 10
	}
	return 0
}

func boolToUint(b bool) uint64 {
	if b {
		return 1
	}
	return 0
}

func writeVarint(buf *bytes.Buffer, field int, v uint64) {
	buf.Write(varint(uint64(field)<<3 | 0))
	buf.Write(varint(v))
}

func writeString(buf *bytes.Buffer, field int, s string) {
	buf.Write(varint(uint64(field)<<3 | 2))
	buf.Write(varint(uint64(len(s))))
	buf.WriteString(s)
}

func writeMessage(buf *bytes.Buffer, field int, sub []byte) {
	buf.Write(varint(uint64(field)<<3 | 2))
	buf.Write(varint(uint64(len(sub))))
	buf.Write(sub)
}

func varint(v uint64) []byte {
	var out []byte
	for v >= 0x80 {
		out = append(out, byte(v)|0x80)
		v >>= 7
	}
	return append(out, byte(v))
}

// handleToolsCall handles the augmentcode/tools/call RPC that the IDE sends
// when a tool has no local implementation. The request wraps a ToolCallRequest
// proto; we decode it, execute the tool, and return the result.
func (s *Server) handleToolsCall(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		connectError(w, "internal", err.Error(), http.StatusInternalServerError)
		return
	}
	log.Printf("tenant: tools/call body (%d bytes): %s", len(body), string(body[:min(len(body), 500)]))

	// The connect+json body may be a JSON object at top level or wrapped in a
	// connect envelope. Try to decode the inner proto JSON.
	var req tools.ToolCallRequest
	if err := json.Unmarshal(body, &req); err != nil {
		connectError(w, "invalid_argument", fmt.Sprintf("cannot decode request: %v", err), http.StatusBadRequest)
		return
	}

	result := s.ToolExecutor.Execute(&req)

	resp := map[string]any{
		"text":     result.Text,
		"is_error": result.IsError,
	}
	w.Header().Set("Content-Type", "application/connect+json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(resp)
}
