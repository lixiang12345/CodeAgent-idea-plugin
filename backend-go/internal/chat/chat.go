// Package chat implements the ChatStream agent loop.  It consumes the IDE's
// full ChatRequest — system prompts, chat history (Exchange[]), code context
// (prefix/suffix/selectedCode), skills, guidelines, tool definitions, editor
// state — builds a rich context window, sends it to the model gateway, handles
// tool-call → tool-result continuations, and streams everything back in the
// connect+json / SSE frame format the IDE expects.
package chat

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"augment-local/internal/state"
)

// ── simulator ──────────────────────────────────────────────────────────

// Simulator renders chat streams.
type Simulator struct {
	Store        *state.Store
	GatewayURL   string
	GatewayModel string
	GatewayKey   string
	HTTPClient   *http.Client
	toolCallSeq  atomic.Int64

	// OnWorkspace, when set, is called with the conversation id and host
	// project root from each chat request's workspace_folders (used to bind
	// ContextEngine to the right project per conversation).
	OnWorkspace func(conversationID, hostRoot string)
	// OnWorkspaceWrite is called when a successful fresh tool result matches a
	// workspace-mutating tool call from chat history.
	OnWorkspaceWrite func(conversationID, hostRoot string)
}

func New(st *state.Store, gatewayURL, gatewayModel string) *Simulator {
	if gatewayModel == "" {
		gatewayModel = "augment-local"
	}
	return &Simulator{
		Store: st, GatewayURL: gatewayURL, GatewayModel: gatewayModel,
		GatewayKey: os.Getenv("MODEL_GATEWAY_API_KEY"),
		HTTPClient: &http.Client{Timeout: 180 * time.Second},
	}
}

// ── flow format ────────────────────────────────────────────────────────

type Flow int

const (
	FlowNDJSON Flow = iota
	FlowSSE
	FlowProto
)

// node models a ChatResultNode in proto3-JSON (snake_case field names).
type node struct {
	ID         int32           `json:"id"`
	Type       string          `json:"type"`
	Content    string          `json:"content,omitempty"`
	ToolUse    *map[string]any `json:"tool_use,omitempty"`
	TokenUsage map[string]any  `json:"token_usage,omitempty"`
	Timestamp  int64           `json:"timestamp_ms,omitempty"`
}

// ── domain types ───────────────────────────────────────────────────────

type modelResponse struct {
	Content    string
	ToolCalls  []modelToolCall
	Streamed   bool // true when content deltas were already streamed to the IDE
	TokenUsage map[string]any
}

type modelToolCall struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

// parsedTurn holds one turn extracted from the IDE's chat_history.
type parsedTurn struct {
	UserMsg       string
	AssistantText string
	ToolCalls     []modelToolCall
	ToolResults   []map[string]any // {tool_call_id, content, is_error}
}

// ── public Stream ──────────────────────────────────────────────────────

func (s *Simulator) Stream(ctx context.Context, w io.Writer, flow Flow, req map[string]any) error {
	cfg := parseRequest(req)

	// Bind this conversation to its host project so ContextEngine indexes and
	// retrieves the right workspace (each chat request carries workspace_folders;
	// conversations in different open projects stay isolated).
	workspaceRoot := ""
	for _, wf := range cfg.WorkspaceFolders {
		if root, _ := wf["folder_root"].(string); root != "" {
			workspaceRoot = root
			if s.OnWorkspace != nil {
				s.OnWorkspace(cfg.ConversationID, root)
			}
			break
		}
	}
	if s.OnWorkspaceWrite != nil && completedWorkspaceWrite(cfg) {
		s.OnWorkspaceWrite(cfg.ConversationID, workspaceRoot)
	}

	ms := func() int64 { return time.Now().UnixMilli() }

	emit := func(msg map[string]any) error {
		line, err := json.Marshal(msg)
		if err != nil {
			return err
		}
		if flow == FlowSSE {
			if _, err := io.WriteString(w, "data: "+string(line)+"\n\n"); err != nil {
				return err
			}
		} else {
			if _, err := w.Write(append(line, '\n')); err != nil {
				return err
			}
		}
		if f, ok := w.(interface{ Flush() error }); ok {
			_ = f.Flush()
		} else if fl, ok := w.(http.Flusher); ok {
			// Raw net/http ResponseWriter: flush after every event so the IDE
			// renders deltas progressively instead of a burst at the end.
			fl.Flush()
		}
		return nil
	}
	emitNode := func(n node) error { return emit(map[string]any{"nodes": []any{n}}) }

	// ── build the system prompt ───────────────────────────────────────
	system := buildSystemPrompt(cfg)

	// ── rebuild messages from the IDE's authoritative chat_history ─────
	messages := s.buildMessagesFromIDE(cfg, system)

	// ── tool definitions → gateway format ─────────────────────────────
	toolDefs := toolDefsFromProto(req["tool_definitions"])

	// ── workspace-questions gate ──────────────────────────────────────
	// The IDE webview sends a hidden chat request asking the model to
	// generate workspace onboarding questions.  We answer locally instead
	// of forwarding to the model gateway, partly to save a round-trip
	// and mostly to avoid leaking a gateway diagnostic message like
	//   [local bridge] Connected; configure MODEL_BASE_URL for model responses.
	// into the workspace-questions popup.
	if isWorkspaceQuestionsRequest(cfg.UserMessage) {
		nid := int32(1)
		_ = emitNode(node{ID: nid, Type: "THINKING",
			Content:   "Generating workspace onboarding questions …",
			Timestamp: ms(),
		})
		nid++

		for _, q := range defaultWorkspaceQuestions(cfg) {
			_ = emitText(emit, q)
		}
		_ = emitNode(node{ID: nid, Type: "MAIN_TEXT_FINISHED", Timestamp: ms()})
		_ = emit(map[string]any{"stop_reason": "END_TURN"})
		return nil
	}

	// ── conversation-title gate ────────────────────────────────────────
	// The IDE webview sends a hidden chat request to generate a short
	// conversation title ("Please provide a clear and concise summary of our
	// conversation so far. The summary must be less than 6 words long…").
	// We handle it as a lightweight unary call, return the title, and — most
	// importantly — do NOT persist it as a normal chat exchange, otherwise the
	// title prompt leaks into the UI as a visible message after the webview
	// restores history.
	if isTitleRequest(cfg.UserMessage) {
		title := s.generateTitle(ctx, cfg)
		if title == "" {
			title = "New Chat"
		}
		_ = emitText(emit, title)
		_ = emitNode(node{ID: 1, Type: "MAIN_TEXT_FINISHED", Timestamp: ms()})
		_ = emit(map[string]any{"stop_reason": "END_TURN"})
		return nil
	}

	// ── agent loop ────────────────────────────────────────────────────
	var finalText strings.Builder
	nodeID := int32(1)

	for iter := 0; iter < 50; iter++ {
		if err := ctx.Err(); err != nil {
			return err
		}

		// Continuation logs retain only payload shape and size. Node values can
		// contain source code, local paths, credentials, or tool output.
		if cfg.IsContinuation {
			log.Printf("chat: continuation iter=%d nodes=%d freshToolResults=%d",
				iter, len(asSlice(req["nodes"])), len(cfg.FreshToolResults))
			for i, n := range asSlice(req["nodes"]) {
				if nm, ok := n.(map[string]any); ok {
					raw, _ := json.Marshal(n)
					log.Printf("chat: node[%d] keys=%v json_bytes=%d", i, sortedMapKeys(nm), len(raw))
				}
			}
			for i, tr := range cfg.FreshToolResults {
				log.Printf("chat: freshToolResult[%d] tool_call_id=%q content_len=%d",
					i, str(tr["tool_call_id"]), len(str(tr["content"])))
			}
		}

		// Emit THINKING node on first iteration of a fresh turn.
		if iter == 0 && !cfg.IsContinuation && cfg.UserMessage != "" {
			thinking := fmt.Sprintf("Let me look at your question about %q and figure out the best approach.",
				truncate(cfg.UserMessage, 60))
			_ = emitNode(node{ID: nodeID, Type: "THINKING", Content: thinking, Timestamp: ms()})
			nodeID++
		}

		// Call the model.
		resp, err := s.callModel(ctx, cfg.Model, messages, toolDefs, emit)
		if err == nil && (resp == nil || (strings.TrimSpace(resp.Content) == "" && len(resp.ToolCalls) == 0)) {
			err = fmt.Errorf("模型网关返回空响应：没有文本或工具调用")
		}
		if err != nil {
			log.Printf("chat: model call error: %v", err)
			errText := fmt.Sprintf("模型调用失败: %v。请检查网关配置。", err)
			_ = emitText(emit, errText)
			_ = emitNode(node{ID: nodeID, Type: "MAIN_TEXT_FINISHED", Timestamp: ms()})
			nodeID++
			_ = emit(map[string]any{"stop_reason": "STOP_REASON_ERROR"})
			s.persistTurn(cfg, errText, nil)
			return nil
		}

		// Model asked to use tools.
		if len(resp.ToolCalls) > 0 {
			// Append assistant message with tool_calls to the conversation.
			messages = append(messages, assistantToolMsg(resp.ToolCalls, resp.Content))

			// Emit TOOL_USE nodes so the IDE executes them locally.
			for _, tc := range resp.ToolCalls {
				toolUse := map[string]any{
					"tool_use_id": tc.ID,
					"tool_name":   tc.Name,
					"input_json":  tc.Arguments,
					"is_partial":  false,
				}
				_ = emitNode(node{ID: nodeID, Type: "TOOL_USE", Timestamp: ms(), ToolUse: &toolUse})
				nodeID++
			}

			// If this iteration follows a continuation, the fresh tool results
			// were already appended in buildMessagesFromIDE — no need to add
			// them again. The model's new tool calls here will need a fresh
			// continuation request from the IDE.
			if !cfg.IsContinuation || iter > 0 {
				usage := completeTokenUsage(resp.TokenUsage, messages, toolDefs, resp.Content)
				_ = emitNode(node{ID: nodeID, Type: "TOKEN_USAGE", TokenUsage: usage, Timestamp: ms()})
				nodeID++
				_ = emitNode(node{ID: nodeID, Type: "MAIN_TEXT_FINISHED", Timestamp: ms()})
				nodeID++
				_ = emit(map[string]any{"stop_reason": "TOOL_USE_REQUESTED"})
				s.persistTurn(cfg, "", resp.ToolCalls)
				return nil
			}

			// Continuation, first iteration: the model got tool results and
			// still wants more tools. Emit and wait for the IDE again.
			usage := completeTokenUsage(resp.TokenUsage, messages, toolDefs, resp.Content)
			_ = emitNode(node{ID: nodeID, Type: "TOKEN_USAGE", TokenUsage: usage, Timestamp: ms()})
			nodeID++
			_ = emitNode(node{ID: nodeID, Type: "MAIN_TEXT_FINISHED", Timestamp: ms()})
			nodeID++
			_ = emit(map[string]any{"stop_reason": "TOOL_USE_REQUESTED"})
			s.persistTurn(cfg, "", resp.ToolCalls)
			return nil
		}

		// Final text answer.
		text := resp.Content
		if text == "" {
			text = "Done."
		}
		finalText.WriteString(text)
		if !resp.Streamed {
			// Non-streamed gateway: emit the whole answer now.
			_ = emitText(emit, text)
		}
		usage := completeTokenUsage(resp.TokenUsage, messages, toolDefs, text)
		_ = emitNode(node{ID: nodeID, Type: "TOKEN_USAGE", TokenUsage: usage, Timestamp: ms()})
		nodeID++
		_ = emitNode(node{ID: nodeID, Type: "MAIN_TEXT_FINISHED", Timestamp: ms()})
		nodeID++
		_ = emit(map[string]any{"stop_reason": "END_TURN"})

		s.persistTurn(cfg, finalText.String(), nil)
		return nil
	}

	// Agent loop exhausted — shouldn't happen.
	if finalText.Len() == 0 {
		_ = emitText(emit, "Agent 循环超过最大迭代次数，已终止。")
	}
	return nil
}

// ── request parsing ────────────────────────────────────────────────────

// requestConfig holds every field we extract from the IDE's ChatRequest.
type requestConfig struct {
	Model          string
	UserMessage    string
	ConversationID string
	TurnID         string
	RequestID      string
	Mode           string // "AGENT" or "CHAT"

	// System-level context.
	SystemPrompt        string
	SystemPromptAppend  string
	UserGuidelines      string
	WorkspaceGuidelines string
	Skills              []map[string]any
	Rules               []any

	// Code context (editor selection).
	Prefix       string
	Suffix       string
	SelectedCode string
	FilePath     string
	Language     string

	// IDE state.
	WorkspaceFolders []map[string]any

	// Conversation history from the IDE (authoritative).
	ChatHistory []map[string]any

	// Tool-result continuation.
	IsContinuation   bool
	FreshToolResults []map[string]any // {tool_call_id, content, is_error}
}

func parseRequest(req map[string]any) requestConfig {
	freshToolResults := extractToolResults(req)
	cfg := requestConfig{
		ChatHistory:      asMapSlice(req["chat_history"]),
		Skills:           asMapSlice(req["skills"]),
		Rules:            asSlice(req["rules"]),
		IsContinuation:   len(freshToolResults) > 0,
		FreshToolResults: freshToolResults,
	}
	cfg.Model, _ = req["model"].(string)
	if cfg.Model == "" {
		cfg.Model = os.Getenv("MODEL_GATEWAY_MODEL")
	}
	cfg.ConversationID, _ = req["conversation_id"].(string)
	cfg.TurnID, _ = req["turn_id"].(string)
	cfg.RequestID = cfg.TurnID
	cfg.Mode, _ = req["mode"].(string)
	cfg.SystemPrompt, _ = req["system_prompt"].(string)
	cfg.SystemPromptAppend, _ = req["system_prompt_append"].(string)
	cfg.UserGuidelines, _ = req["user_guidelines"].(string)
	cfg.WorkspaceGuidelines, _ = req["workspace_guidelines"].(string)
	cfg.Prefix, _ = req["prefix"].(string)
	cfg.Suffix, _ = req["suffix"].(string)
	cfg.SelectedCode, _ = req["selected_code"].(string)
	cfg.FilePath, _ = req["path"].(string)
	cfg.Language, _ = req["lang"].(string)

	// Extract workspace folders from ide_state_node.
	if nodes := asSlice(req["nodes"]); len(nodes) > 0 {
		for _, n := range nodes {
			nm, _ := n.(map[string]any)
			if ide, ok := nm["ide_state_node"].(map[string]any); ok {
				if wfs, ok := ide["workspace_folders"].([]any); ok {
					for _, wf := range wfs {
						if wfm, ok := wf.(map[string]any); ok {
							cfg.WorkspaceFolders = append(cfg.WorkspaceFolders, wfm)
						}
					}
				}
			}
		}
	}

	// User message: prefer top-level "message", fall back to textNode in nodes.
	cfg.UserMessage = extractMessage(req)

	return cfg
}

func completedWorkspaceWrite(cfg requestConfig) bool {
	writeCalls := make(map[string]bool)
	for _, exchange := range cfg.ChatHistory {
		for _, call := range parseExchange(exchange).ToolCalls {
			switch call.Name {
			case "str-replace-editor", "save-file", "remove-files", "apply_patch":
				writeCalls[call.ID] = true
			}
		}
	}
	for _, result := range cfg.FreshToolResults {
		if !bo(result["is_error"]) && writeCalls[str(result["tool_call_id"])] {
			return true
		}
	}
	return false
}

// ── system prompt builder ──────────────────────────────────────────────

func buildSystemPrompt(cfg requestConfig) string {
	var b strings.Builder

	// 1. Core agent identity.
	b.WriteString("You are an AI coding assistant integrated into the Augment IDE plugin. ")
	b.WriteString("You work inside a real IDE — IntelliJ / VS Code — with direct access to the user's project files, terminal, git history, linter diagnostics, and web search. ")
	b.WriteString("Your available actions depend on the active IDE mode and the tools provided for the current request.\n\n")

	// 2. IDE-provided system prompt (highest priority).
	if cfg.SystemPrompt != "" {
		b.WriteString("## System\n")
		b.WriteString(cfg.SystemPrompt)
		b.WriteString("\n\n")
	}

	if cfg.SystemPromptAppend != "" {
		b.WriteString(cfg.SystemPromptAppend)
		b.WriteString("\n\n")
	}

	// 3. Workspace context.
	if len(cfg.WorkspaceFolders) > 0 {
		b.WriteString("## Workspace\n")
		for _, wf := range cfg.WorkspaceFolders {
			root, _ := wf["folder_root"].(string)
			repo, _ := wf["repository_root"].(string)
			if root != "" {
				b.WriteString(fmt.Sprintf("- Project root: %s", root))
				if repo != "" && repo != root {
					b.WriteString(fmt.Sprintf(" (repository: %s)", repo))
				}
				b.WriteString("\n")
			}
		}
		b.WriteString("\n")
	}

	// 4. Guidelines.
	if cfg.UserGuidelines != "" {
		b.WriteString("## User Guidelines\n")
		b.WriteString(cfg.UserGuidelines)
		b.WriteString("\n\n")
	}
	if cfg.WorkspaceGuidelines != "" {
		b.WriteString("## Workspace Guidelines\n")
		b.WriteString(cfg.WorkspaceGuidelines)
		b.WriteString("\n\n")
	}

	// 5. Installed skills.
	if len(cfg.Skills) > 0 {
		b.WriteString("## Available Skills\n")
		for _, sk := range cfg.Skills {
			name, _ := sk["name"].(string)
			desc, _ := sk["description"].(string)
			if name != "" {
				b.WriteString(fmt.Sprintf("- **%s**: %s\n", name, desc))
			}
		}
		b.WriteString("\n")
	}

	// 6. Rules.
	if len(cfg.Rules) > 0 {
		b.WriteString("## Rules\n")
		for _, r := range cfg.Rules {
			if rm, ok := r.(map[string]any); ok {
				if txt, ok := rm["content"].(string); ok && txt != "" {
					b.WriteString("- " + txt + "\n")
				}
			}
		}
		b.WriteString("\n")
	}

	// 7. Codebase exploration strategy.
	b.WriteString("## Codebase Exploration\n")
	b.WriteString("- Start with one high-signal information-gathering pass; gather more only when evidence remains missing.\n")
	b.WriteString("- When the relevant location is unknown, start with `codebase-retrieval` and describe the behavior or architecture you need to find.\n")
	b.WriteString("- When history matters, use `git-commit-retrieval` for prior implementation or rationale; use codebase retrieval for the current working tree.\n")
	b.WriteString("- Treat Context Engine results as a map to relevant code, not a complete file dump.\n")
	b.WriteString("- Once a concrete file is known, use `view` or `view-range-untruncated` to read the required implementation.\n")
	b.WriteString("- Use `grep-search` only for exact symbols, literals, error messages, patterns, counts, or repository-wide references. Its output may be truncated.\n")
	b.WriteString("- Never reconstruct a file through repeated `grep-search` calls. Read the known file instead.\n")
	b.WriteString("- If semantic retrieval is empty, irrelevant, or incomplete, reformulate the information request only when missing evidence requires it; do not repeat the same query.\n")
	b.WriteString("- Stop using tools as soon as you have enough evidence to answer or act. Do not call another tool merely because one is available.\n\n")

	// 8. Operating instructions.
	b.WriteString("## Instructions\n")
	b.WriteString("- Respect the active IDE mode. In Quick Ask or ask mode, use only read-only tools; do not modify files, run side-effecting commands, or manage tasks.\n")
	b.WriteString("- In Agent mode, make changes only when the user requests them. Do not ask the user to do work that an available tool can perform safely.\n")
	b.WriteString("- When the active mode permits it, use task tools for complex or multi-file work and keep the plan short; skip task management for trivial or read-only requests.\n")
	b.WriteString("- After each tool result, decide whether the evidence is sufficient. Answer when it is; otherwise choose the single next tool that supplies missing evidence.\n")
	b.WriteString("- After edits, run the smallest relevant tests or diagnostics and inspect the resulting diff before reporting success.\n")
	b.WriteString("- Do not commit, push, install dependencies, deploy, or change external task state unless the user explicitly asks.\n")
	b.WriteString("- Always respond in the language the user writes in.\n")
	b.WriteString("- Use absolute paths when working with files.\n")
	b.WriteString("- Read a file before editing it. Verify changes after making them.\n")
	b.WriteString("- When running terminal commands, describe what you're doing before executing.\n")
	b.WriteString("- Report findings directly — no legal disclaimers, no moralizing.\n")

	return b.String()
}

// ── message building from IDE's chat_history ───────────────────────────

func (s *Simulator) buildMessagesFromIDE(cfg requestConfig, system string) []map[string]any {
	var msgs []map[string]any

	// System message first.
	if system != "" {
		msgs = append(msgs, map[string]any{"role": "system", "content": system})
	}

	// The IDE stores a tool call in one exchange's response_nodes and its result
	// in the next exchange's request_nodes. Parse all exchanges first so results
	// can be associated with their calls by tool_use_id.
	turns := make([]parsedTurn, 0, len(cfg.ChatHistory))
	toolResults := make(map[string]map[string]any)
	for _, ex := range cfg.ChatHistory {
		turn := parseExchange(ex)
		turns = append(turns, turn)
		for _, tr := range turn.ToolResults {
			if id := str(tr["tool_call_id"]); id != "" {
				toolResults[id] = tr
			}
		}
	}

	for _, turn := range turns {

		// User message.
		if turn.UserMsg != "" {
			msgs = append(msgs, map[string]any{"role": "user", "content": turn.UserMsg})
		}

		// Assistant: if there are tool calls, emit assistant + tool results.
		if len(turn.ToolCalls) > 0 {
			msgs = append(msgs, assistantToolMsg(turn.ToolCalls, turn.AssistantText))
			for _, tc := range turn.ToolCalls {
				if tr, ok := toolResults[tc.ID]; ok {
					msgs = append(msgs, toolResultMsg(tr))
				} else {
					// Keep the OpenAI message structure valid when the IDE genuinely
					// did not record a result for this tool call.
					msgs = append(msgs, map[string]any{
						"role":         "tool",
						"tool_call_id": tc.ID,
						"content":      "[tool executed by IDE]",
					})
				}
			}
			continue
		}

		// Plain assistant text.
		if turn.AssistantText != "" {
			msgs = append(msgs, map[string]any{"role": "assistant", "content": turn.AssistantText})
		}
	}

	// Current request.
	if cfg.IsContinuation && len(cfg.FreshToolResults) > 0 {
		// Collect the tool call IDs from fresh results so we can replace
		// the placeholder entries from the prior buildMessagesFromIDE pass.
		seenIDs := make(map[string]bool)
		for _, tr := range cfg.FreshToolResults {
			if id := str(tr["tool_call_id"]); id != "" {
				seenIDs[id] = true
			}
		}
		// Walk backwards: remove placeholder tool-result messages whose
		// tool_call_id now has a real result.
		for i := len(msgs) - 1; i >= 0; i-- {
			m := msgs[i]
			if m["role"] == "tool" && seenIDs[str(m["tool_call_id"])] {
				msgs = append(msgs[:i], msgs[i+1:]...)
			}
		}
		// Append the fresh tool results.
		for _, tr := range cfg.FreshToolResults {
			msgs = append(msgs, toolResultMsg(tr))
		}
	} else if cfg.UserMessage != "" {
		// Fresh user message with code context.
		content := cfg.UserMessage
		if cfg.SelectedCode != "" || cfg.Prefix != "" {
			content = buildCodeContextMessage(cfg)
		}
		msgs = append(msgs, map[string]any{"role": "user", "content": content})
	}

	return msgs
}

// parseExchange extracts a turn from one IDE Exchange entry.
// Exchange: {request_message, response_text, request_id, request_nodes[], response_nodes[]}
func parseExchange(ex map[string]any) parsedTurn {
	turn := parsedTurn{
		UserMsg:       str(ex["request_message"]),
		AssistantText: str(ex["response_text"]),
	}

	// request_nodes: user text and tool results from the previous exchange.
	if rns, ok := ex["request_nodes"].([]any); ok {
		for _, rn := range rns {
			nm, _ := rn.(map[string]any)
			if tn, ok := nm["text_node"].(map[string]any); ok {
				if c := str(tn["content"]); c != "" && turn.UserMsg == "" {
					turn.UserMsg = c
				}
			}
			if txt, ok := nm["textNode"].(map[string]any); ok {
				if c := str(txt["content"]); c != "" && turn.UserMsg == "" {
					turn.UserMsg = c
				}
			}
			if tr := parseToolResultNode(nm); tr != nil {
				turn.ToolResults = append(turn.ToolResults, tr)
			}
		}
	}

	// response_nodes: assistant's response (text + tool_use nodes).
	if rns, ok := ex["response_nodes"].([]any); ok {
		for _, rn := range rns {
			nm, _ := rn.(map[string]any)
			// Tool use nodes.
			if tu, ok := nm["tool_use"].(map[string]any); ok {
				tc := modelToolCall{
					ID:        str(tu["tool_use_id"]),
					Name:      str(tu["tool_name"]),
					Arguments: str(tu["input_json"]),
				}
				if tc.ID != "" && tc.Name != "" {
					turn.ToolCalls = append(turn.ToolCalls, tc)
				}
			}
			if tr := parseToolResultNode(nm); tr != nil {
				turn.ToolResults = append(turn.ToolResults, tr)
			}
			// Collapse text from nodes that have inline content.
			if c := str(nm["content"]); c != "" && !strings.Contains(c, "tool_use") {
				if turn.AssistantText == "" {
					turn.AssistantText = c
				}
			}
		}
	}

	// If response_text wasn't in nodes, use the top-level field.
	if turn.AssistantText == "" {
		turn.AssistantText = str(ex["response_text"])
	}

	return turn
}

func parseToolResultNode(n map[string]any) map[string]any {
	tr, _ := n["tool_result_node"].(map[string]any)
	if tr == nil {
		tr, _ = n["toolResultNode"].(map[string]any)
	}
	if tr == nil {
		return nil
	}
	id := str(tr["tool_use_id"])
	if id == "" {
		id = str(tr["toolUseId"])
	}
	if id == "" {
		return nil
	}
	isError := bo(tr["is_error"])
	if _, ok := tr["is_error"]; !ok {
		isError = bo(tr["isError"])
	}
	return map[string]any{
		"tool_call_id": id,
		"content":      str(tr["content"]),
		"is_error":     isError,
	}
}

func buildCodeContextMessage(cfg requestConfig) string {
	var b strings.Builder
	b.WriteString(cfg.UserMessage)
	b.WriteString("\n\n")

	if cfg.FilePath != "" {
		b.WriteString(fmt.Sprintf("Current file: `%s`", cfg.FilePath))
		if cfg.Language != "" {
			b.WriteString(fmt.Sprintf(" (%s)", cfg.Language))
		}
		b.WriteString("\n\n")
	}

	if cfg.Prefix != "" || cfg.SelectedCode != "" || cfg.Suffix != "" {
		b.WriteString("Code context:\n```")
		if cfg.Language != "" {
			b.WriteString(cfg.Language)
		}
		b.WriteString("\n")
		if cfg.Prefix != "" {
			b.WriteString(cfg.Prefix)
		}
		if cfg.SelectedCode != "" {
			b.WriteString(cfg.SelectedCode)
		}
		if cfg.Suffix != "" {
			b.WriteString(cfg.Suffix)
		}
		b.WriteString("\n```\n")
	}

	return b.String()
}

// ── model gateway ──────────────────────────────────────────────────────

func (s *Simulator) callModel(ctx context.Context, model string, messages []map[string]any, toolDefs []map[string]any, emit func(map[string]any) error) (*modelResponse, error) {
	baseURL := s.GatewayURL
	if baseURL == "" {
		return nil, fmt.Errorf("MODEL_GATEWAY_URL not set")
	}

	isAnthropic := strings.HasPrefix(strings.ToLower(model), "claude-")

	if isAnthropic {
		return s.callAnthropic(ctx, baseURL, model, messages, toolDefs, emit)
	}
	return s.callOpenAI(ctx, baseURL, model, messages, toolDefs, emit)
}

func (s *Simulator) callOpenAI(ctx context.Context, baseURL, model string, messages, toolDefs []map[string]any, emit func(map[string]any) error) (*modelResponse, error) {
	messages = openAIMessages(messages)
	reasoning := os.Getenv("MODEL_GATEWAY_REASONING_EFFORT")
	if reasoning == "" {
		reasoning = "high"
	}
	body := map[string]any{
		"model":            model,
		"messages":         messages,
		"temperature":      0.7,
		"reasoning_effort": reasoning,
		"stream":           true,
		"stream_options":   map[string]any{"include_usage": true},
	}
	if len(toolDefs) > 0 {
		body["tools"] = toolDefs
		body["tool_choice"] = "auto"
	}

	bodyBytes, _ := json.Marshal(body)
	log.Printf("chat: openai request model=%s body=%d bytes msgs=%d tools=%d reasoning=%s",
		model, len(bodyBytes), len(messages), len(toolDefs), reasoning)

	url := strings.TrimSuffix(baseURL, "/v1")
	url = strings.TrimSuffix(url, "/")
	if !strings.Contains(url, "/chat/completions") {
		url += "/v1/chat/completions"
	}

	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	if s.GatewayKey != "" {
		req.Header.Set("Authorization", "Bearer "+s.GatewayKey)
	}

	resp, err := s.HTTPClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		var errBody struct {
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&errBody)
		return nil, fmt.Errorf("openai: status %d: %s", resp.StatusCode, errBody.Error.Message)
	}

	mr := &modelResponse{Streamed: true}

	// Tool calls arrive incrementally across chunks, keyed by index.
	var toolCalls []struct {
		ID   string
		Name string
		Args string
	}

	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Text()
		if !strings.HasPrefix(line, "data:") {
			if message := openAIErrorMessage([]byte(strings.TrimSpace(line))); message != "" {
				return nil, fmt.Errorf("openai: %s", message)
			}
			continue
		}
		data := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if data == "[DONE]" {
			break
		}

		var chunk struct {
			Choices []struct {
				Delta struct {
					Content   string `json:"content"`
					ToolCalls []struct {
						Index    int    `json:"index"`
						ID       string `json:"id"`
						Function struct {
							Name      string `json:"name"`
							Arguments string `json:"arguments"`
						} `json:"function"`
					} `json:"tool_calls"`
				} `json:"delta"`
				FinishReason string `json:"finish_reason"`
			} `json:"choices"`
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
			Usage map[string]any `json:"usage"`
		}
		if err := json.Unmarshal([]byte(data), &chunk); err != nil {
			continue
		}
		if chunk.Error.Message != "" {
			return nil, fmt.Errorf("openai: %s", chunk.Error.Message)
		}
		mr.TokenUsage = mergeTokenUsage(mr.TokenUsage, normalizeOpenAIUsage(chunk.Usage))

		for _, c := range chunk.Choices {
			if d := c.Delta.Content; d != "" {
				mr.Content += d
				if err := streamText(emit, d); err != nil {
					return nil, err
				}
			}
			for _, tc := range c.Delta.ToolCalls {
				for len(toolCalls) <= tc.Index {
					toolCalls = append(toolCalls, struct{ ID, Name, Args string }{})
				}
				if tc.ID != "" {
					toolCalls[tc.Index].ID = tc.ID
				}
				if tc.Function.Name != "" {
					toolCalls[tc.Index].Name = tc.Function.Name
				}
				toolCalls[tc.Index].Args += tc.Function.Arguments
			}
		}
	}
	if err := sc.Err(); err != nil {
		return nil, fmt.Errorf("openai stream: %w", err)
	}

	for _, tc := range toolCalls {
		if tc.ID == "" && tc.Name == "" {
			continue
		}
		mr.ToolCalls = append(mr.ToolCalls, modelToolCall{
			ID: tc.ID, Name: tc.Name, Arguments: tc.Args,
		})
	}
	log.Printf("chat: openai stream content=%d chars, tools=%d", len(mr.Content), len(mr.ToolCalls))

	// Some gateways (e.g. krill-ai codex) return an empty response when
	// reasoning_effort=xhigh — no content and no tool calls. Degrade to a low
	// effort non-streaming retry so simple requests still get an answer.
	if reasoning == "xhigh" && len(mr.Content) == 0 && len(mr.ToolCalls) == 0 {
		log.Printf("chat: openai xhigh returned empty; retrying with low reasoning effort")
		retry, retryErr := s.retryOpenAILow(ctx, url, model, messages, toolDefs)
		if retryErr != nil {
			return nil, fmt.Errorf("模型网关返回空响应，低推理重试失败: %w", retryErr)
		}
		if retry != nil && (retry.Content != "" || len(retry.ToolCalls) > 0) {
			if retry.Content != "" {
				if err := streamText(emit, retry.Content); err != nil {
					return nil, err
				}
				retry.Streamed = true
			}
			return retry, nil
		}
		return nil, fmt.Errorf("模型网关返回空响应：低推理重试仍无文本或工具调用")
	}
	return mr, nil
}

// retryOpenAILow makes a non-streaming OpenAI-compatible call with
// reasoning_effort=low and returns the complete assistant message, including
// tool calls, when the primary streaming call returns nothing.
func (s *Simulator) retryOpenAILow(ctx context.Context, url, model string, messages, toolDefs []map[string]any) (*modelResponse, error) {
	body := map[string]any{
		"model":            model,
		"messages":         messages,
		"temperature":      0.5,
		"reasoning_effort": "low",
		"stream":           false,
	}
	if len(toolDefs) > 0 {
		body["tools"] = toolDefs
		body["tool_choice"] = "auto"
	}
	bodyBytes, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(bodyBytes))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if s.GatewayKey != "" {
		req.Header.Set("Authorization", "Bearer "+s.GatewayKey)
	}
	resp, err := s.HTTPClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("status %d: %s", resp.StatusCode, truncate(strings.TrimSpace(string(raw)), 500))
	}
	var out struct {
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
		Choices []struct {
			Message struct {
				Content   string `json:"content"`
				ToolCalls []struct {
					ID       string `json:"id"`
					Function struct {
						Name      string `json:"name"`
						Arguments string `json:"arguments"`
					} `json:"function"`
				} `json:"tool_calls"`
			} `json:"message"`
		} `json:"choices"`
		Usage map[string]any `json:"usage"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		log.Printf("chat: retryOpenAILow decode error: %v", err)
		return nil, err
	}
	if out.Error.Message != "" {
		return nil, fmt.Errorf("openai: %s", out.Error.Message)
	}
	if len(out.Choices) > 0 {
		message := out.Choices[0].Message
		retry := &modelResponse{Content: message.Content, TokenUsage: normalizeOpenAIUsage(out.Usage)}
		for _, tc := range message.ToolCalls {
			if tc.ID == "" && tc.Function.Name == "" {
				continue
			}
			retry.ToolCalls = append(retry.ToolCalls, modelToolCall{
				ID: tc.ID, Name: tc.Function.Name, Arguments: tc.Function.Arguments,
			})
		}
		log.Printf("chat: retryOpenAILow returned %d chars, tools=%d", len(retry.Content), len(retry.ToolCalls))
		return retry, nil
	}
	log.Printf("chat: retryOpenAILow returned no choices")
	return &modelResponse{}, nil
}

func openAIErrorMessage(data []byte) string {
	if len(data) == 0 {
		return ""
	}
	var envelope struct {
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if json.Unmarshal(data, &envelope) != nil {
		return ""
	}
	return envelope.Error.Message
}

func (s *Simulator) callAnthropic(ctx context.Context, baseURL, model string, messages, toolDefs []map[string]any, emit func(map[string]any) error) (*modelResponse, error) {
	// Convert messages and tools to Anthropic format.
	var system string
	var anthropicMsgs []map[string]any

	for _, m := range messages {
		role, _ := m["role"].(string)
		content := m["content"]

		if role == "system" {
			if s, ok := content.(string); ok {
				system += s + "\n"
			}
			continue
		}

		if role == "tool" {
			// Tool result → user message with tool_result block.
			block := map[string]any{"type": "tool_result"}
			if tcID, ok := m["tool_call_id"].(string); ok {
				block["tool_use_id"] = tcID
			}
			if c, ok := content.(string); ok {
				block["content"] = c
			}
			if ie, ok := m["is_error"].(bool); ok && ie {
				block["is_error"] = true
			}
			anthropicMsgs = append(anthropicMsgs, map[string]any{
				"role":    "user",
				"content": []any{block},
			})
			continue
		}

		if role == "assistant" {
			if tcs, ok := m["tool_calls"].([]any); ok && len(tcs) > 0 {
				blocks := make([]any, 0, len(tcs))
				if txt, _ := content.(string); txt != "" {
					blocks = append(blocks, map[string]any{"type": "text", "text": txt})
				}
				for _, tc := range tcs {
					tcm, _ := tc.(map[string]any)
					fn, _ := tcm["function"].(map[string]any)
					inputStr, _ := fn["arguments"].(string)
					blocks = append(blocks, map[string]any{
						"type":  "tool_use",
						"id":    tcm["id"],
						"name":  fn["name"],
						"input": parseJSON(inputStr),
					})
				}
				anthropicMsgs = append(anthropicMsgs, map[string]any{
					"role":    "assistant",
					"content": blocks,
				})
				continue
			}
		}

		// Plain text message.
		if txt, ok := content.(string); ok && txt != "" {
			anthropicMsgs = append(anthropicMsgs, map[string]any{
				"role":    role,
				"content": []any{map[string]any{"type": "text", "text": txt}},
			})
		}
	}

	// Convert tools.
	var anthropicTools []map[string]any
	for _, td := range toolDefs {
		fn, _ := td["function"].(map[string]any)
		if fn == nil {
			continue
		}
		anthropicTools = append(anthropicTools, map[string]any{
			"name":         fn["name"],
			"description":  fn["description"],
			"input_schema": fn["parameters"],
		})
	}

	body := map[string]any{
		"model":      model,
		"max_tokens": 8192,
		"stream":     true,
		"messages":   anthropicMsgs,
	}
	// Extended-thinking tool turns must replay the provider's signed thinking
	// blocks verbatim on continuation. The IDE history does not retain those
	// provider blocks, so only enable thinking for requests without tools.
	if len(anthropicTools) == 0 {
		body["thinking"] = map[string]any{
			"type":          "enabled",
			"budget_tokens": 4000,
		}
	}
	if system != "" {
		body["system"] = strings.TrimSpace(system)
	}
	if len(anthropicTools) > 0 {
		body["tools"] = anthropicTools
	}

	bodyBytes, _ := json.Marshal(body)

	url := strings.TrimSuffix(baseURL, "/v1")
	url = strings.TrimSuffix(url, "/")
	if !strings.Contains(url, "/messages") {
		url += "/v1/messages"
	}

	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("anthropic-version", "2023-06-01")
	if s.GatewayKey != "" {
		req.Header.Set("x-api-key", s.GatewayKey)
	}

	resp, err := s.HTTPClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		var errBody struct {
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&errBody)
		return nil, fmt.Errorf("anthropic: status %d: %s", resp.StatusCode, errBody.Error.Message)
	}

	mr := &modelResponse{Streamed: true}

	type pendingTool struct {
		ID    string
		Name  string
		Input string // accumulated partial_json
	}
	var pending []*pendingTool
	var cur *pendingTool

	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for sc.Scan() {
		line := sc.Text()
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		data := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if data == "[DONE]" {
			break
		}

		var ev struct {
			Type    string `json:"type"`
			Message *struct {
				Usage map[string]any `json:"usage"`
			} `json:"message"`
			ContentBlock *struct {
				Type  string         `json:"type"`
				Text  string         `json:"text"`
				ID    string         `json:"id"`
				Name  string         `json:"name"`
				Input map[string]any `json:"input"`
			} `json:"content_block"`
			Delta *struct {
				Type        string `json:"type"`
				Text        string `json:"text"`
				PartialJSON string `json:"partial_json"`
			} `json:"delta"`
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
			Usage map[string]any `json:"usage"`
		}
		if err := json.Unmarshal([]byte(data), &ev); err != nil {
			continue
		}
		if ev.Error.Message != "" {
			return nil, fmt.Errorf("anthropic: %s", ev.Error.Message)
		}
		if ev.Message != nil {
			mr.TokenUsage = mergeTokenUsage(mr.TokenUsage, normalizeAnthropicUsage(ev.Message.Usage))
		}
		mr.TokenUsage = mergeTokenUsage(mr.TokenUsage, normalizeAnthropicUsage(ev.Usage))

		switch ev.Type {
		case "content_block_start":
			if ev.ContentBlock != nil && ev.ContentBlock.Type == "tool_use" {
				cur = &pendingTool{ID: ev.ContentBlock.ID, Name: ev.ContentBlock.Name}
				if inp := ev.ContentBlock.Input; len(inp) > 0 {
					if b, err := json.Marshal(inp); err == nil && string(b) != "{}" {
						cur.Input = string(b)
					}
				}
				pending = append(pending, cur)
			}
		case "content_block_delta":
			if ev.Delta == nil {
				continue
			}
			switch ev.Delta.Type {
			case "text_delta":
				mr.Content += ev.Delta.Text
				if err := streamText(emit, ev.Delta.Text); err != nil {
					return nil, err
				}
			case "input_json_delta":
				if cur != nil {
					cur.Input += ev.Delta.PartialJSON
				}
			}
		}
	}
	if err := sc.Err(); err != nil {
		return nil, fmt.Errorf("anthropic stream: %w", err)
	}

	for _, p := range pending {
		if p.ID == "" || p.Name == "" {
			continue
		}
		mr.ToolCalls = append(mr.ToolCalls, modelToolCall{
			ID: p.ID, Name: p.Name, Arguments: p.Input,
		})
	}
	log.Printf("chat: anthropic stream content=%d chars, tools=%d", len(mr.Content), len(mr.ToolCalls))
	for i, tc := range mr.ToolCalls {
		var args map[string]any
		_ = json.Unmarshal([]byte(tc.Arguments), &args)
		log.Printf("chat: anthropic tool[%d]=%q args_len=%d arg_keys=%v",
			i, tc.Name, len(tc.Arguments), sortedMapKeys(args))
	}
	return mr, nil
}

// ── tool def conversion ────────────────────────────────────────────────

func toolDefsFromProto(raw any) []map[string]any {
	arr, ok := raw.([]any)
	if !ok {
		return nil
	}
	var out []map[string]any
	for _, item := range arr {
		im, _ := item.(map[string]any)
		if im == nil {
			continue
		}
		// IDE sends from listRemoteTools: {toolDefinition: {name, ...}} or flat {name, ...}.
		td, ok := im["toolDefinition"].(map[string]any)
		if !ok {
			td = im
		}
		name, _ := td["name"].(string)
		if name == "" {
			continue
		}
		desc, _ := td["description"].(string)
		schemaStr, _ := td["input_schema_json"].(string)

		var params map[string]any
		if schemaStr != "" {
			json.Unmarshal([]byte(schemaStr), &params)
		}
		if params == nil {
			params = map[string]any{"type": "object", "properties": map[string]any{}}
		}

		out = append(out, map[string]any{
			"type": "function",
			"function": map[string]any{
				"name":        name,
				"description": desc,
				"parameters":  params,
			},
		})
	}
	return out
}

// ── message helpers ────────────────────────────────────────────────────

func assistantToolMsg(toolCalls []modelToolCall, prefix string) map[string]any {
	var arr []any
	for _, tc := range toolCalls {
		arr = append(arr, map[string]any{
			"id":   tc.ID,
			"type": "function",
			"function": map[string]any{
				"name":      tc.Name,
				"arguments": tc.Arguments,
			},
		})
	}
	m := map[string]any{"role": "assistant", "tool_calls": arr}
	if prefix != "" {
		m["content"] = prefix
	}
	return m
}

func toolResultMsg(tr map[string]any) map[string]any {
	return map[string]any{
		"role":         "tool",
		"tool_call_id": tr["tool_call_id"],
		"content":      tr["content"],
		"is_error":     bo(tr["is_error"]),
	}
}

// openAIMessages removes provider-neutral metadata that is not part of the
// OpenAI message schema. Tool failures remain visible to the model as text.
func openAIMessages(messages []map[string]any) []map[string]any {
	out := make([]map[string]any, 0, len(messages))
	for _, message := range messages {
		copy := make(map[string]any, len(message))
		for key, value := range message {
			copy[key] = value
		}
		if copy["role"] == "tool" {
			isError := bo(copy["is_error"])
			delete(copy, "is_error")
			if isError {
				content := str(copy["content"])
				if content == "" {
					copy["content"] = "[tool error]"
				} else {
					copy["content"] = "[tool error]\n" + content
				}
			}
		}
		out = append(out, copy)
	}
	return out
}

// ── request extraction ─────────────────────────────────────────────────

func extractMessage(req map[string]any) string {
	if msg, _ := req["message"].(string); msg != "" {
		return msg
	}
	for _, n := range asSlice(req["nodes"]) {
		nm, _ := n.(map[string]any)
		if tn, ok := nm["text_node"].(map[string]any); ok {
			if c := str(tn["content"]); c != "" {
				return c
			}
		}
		if tn, ok := nm["textNode"].(map[string]any); ok {
			if c := str(tn["content"]); c != "" {
				return c
			}
		}
	}
	return ""
}

func extractToolResults(req map[string]any) []map[string]any {
	var out []map[string]any
	for _, n := range asSlice(req["nodes"]) {
		nm, _ := n.(map[string]any)
		if result := parseToolResultNode(nm); result != nil {
			out = append(out, result)
		}
	}
	return out
}

// ── persistence ────────────────────────────────────────────────────────

func (s *Simulator) persistTurn(cfg requestConfig, finalText string, toolCalls []modelToolCall) {
	if cfg.ConversationID != "" {
		workspaceID := ""
		for _, folder := range cfg.WorkspaceFolders {
			if root, _ := folder["folder_root"].(string); root != "" {
				workspaceID = root
				break
			}
		}
		s.Store.CreateConversation(cfg.ConversationID, workspaceID, "", false)
	}
	s.Store.AppendExchange(cfg.ConversationID, &state.Exchange{
		RequestID:    cfg.RequestID,
		RequestMsg:   cfg.UserMessage,
		ResponseText: finalText,
		TurnID:       cfg.TurnID,
		ToolCalls:    stateToolCalls(toolCalls),
	})
}

func stateToolCalls(tcs []modelToolCall) []state.ToolCall {
	out := make([]state.ToolCall, 0, len(tcs))
	for _, tc := range tcs {
		out = append(out, state.ToolCall{ID: tc.ID, Name: tc.Name, Arguments: tc.Arguments})
	}
	return out
}

// ── emit helpers ───────────────────────────────────────────────────────

func emitText(emit func(map[string]any) error, text string) error {
	for _, delta := range splitDeltas(text, 24) {
		if err := emit(map[string]any{"text": delta}); err != nil {
			return err
		}
	}
	return nil
}

// streamText emits one gateway stream delta as text events, splitting very
// large deltas so the IDE renders progressively.
func streamText(emit func(map[string]any) error, s string) error {
	for _, delta := range splitDeltas(s, 48) {
		if err := emit(map[string]any{"text": delta}); err != nil {
			return err
		}
	}
	return nil
}

const (
	defaultMaxContextTokens = 200000
	defaultMaxOutputTokens  = 8192
)

func completeTokenUsage(usage map[string]any, messages, tools []map[string]any, response string) map[string]any {
	complete := make(map[string]any, len(usage)+4)
	for key, value := range usage {
		complete[key] = value
	}
	if _, ok := tokenCount(complete["input_tokens"]); !ok {
		complete["input_tokens"] = estimateTokens(map[string]any{
			"messages": messages,
			"tools":    tools,
		})
	}
	if _, ok := tokenCount(complete["output_tokens"]); !ok {
		complete["output_tokens"] = estimateTokens(response)
	}
	complete["max_context_tokens"] = positiveEnvInt("MODEL_GATEWAY_MAX_CONTEXT_TOKENS", defaultMaxContextTokens)
	complete["max_output_tokens"] = positiveEnvInt("MODEL_GATEWAY_MAX_OUTPUT_TOKENS", defaultMaxOutputTokens)
	return complete
}

func normalizeOpenAIUsage(raw map[string]any) map[string]any {
	if len(raw) == 0 {
		return nil
	}
	usage := make(map[string]any)
	copyFirstTokenCount(usage, "input_tokens", raw, "input_tokens", "prompt_tokens")
	copyFirstTokenCount(usage, "output_tokens", raw, "output_tokens", "completion_tokens")
	copyFirstTokenCount(usage, "cache_read_input_tokens", raw, "cache_read_input_tokens")
	for _, detailsKey := range []string{"input_tokens_details", "prompt_tokens_details"} {
		details, _ := raw[detailsKey].(map[string]any)
		copyFirstTokenCount(usage, "cache_read_input_tokens", details, "cached_tokens")
	}
	return usage
}

func normalizeAnthropicUsage(raw map[string]any) map[string]any {
	if len(raw) == 0 {
		return nil
	}
	usage := make(map[string]any)
	for _, key := range []string{
		"input_tokens",
		"output_tokens",
		"cache_read_input_tokens",
		"cache_creation_input_tokens",
	} {
		copyFirstTokenCount(usage, key, raw, key)
	}
	return usage
}

func mergeTokenUsage(dst map[string]any, src map[string]any) map[string]any {
	if len(src) == 0 {
		return dst
	}
	if dst == nil {
		dst = make(map[string]any, len(src))
	}
	for key, value := range src {
		dst[key] = value
	}
	return dst
}

func copyFirstTokenCount(dst map[string]any, dstKey string, src map[string]any, srcKeys ...string) {
	if _, exists := dst[dstKey]; exists || len(src) == 0 {
		return
	}
	for _, key := range srcKeys {
		if count, ok := tokenCount(src[key]); ok {
			dst[dstKey] = count
			return
		}
	}
}

func tokenCount(value any) (int, bool) {
	var count int
	switch n := value.(type) {
	case int:
		count = n
	case int32:
		count = int(n)
	case int64:
		count = int(n)
	case float64:
		count = int(n)
	case json.Number:
		parsed, err := strconv.Atoi(n.String())
		if err != nil {
			return 0, false
		}
		count = parsed
	default:
		return 0, false
	}
	return count, count >= 0
}

func estimateTokens(value any) int {
	encoded, err := json.Marshal(value)
	if err != nil || len(encoded) == 0 {
		return 0
	}
	return max(1, (len(encoded)+3)/4)
}

func positiveEnvInt(name string, fallback int) int {
	value, err := strconv.Atoi(strings.TrimSpace(os.Getenv(name)))
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}

// ── utilities ──────────────────────────────────────────────────────────

func str(v any) string {
	s, _ := v.(string)
	return s
}

func bo(v any) bool {
	b, _ := v.(bool)
	return b
}

func asSlice(v any) []any {
	if a, ok := v.([]any); ok {
		return a
	}
	return nil
}

func asMapSlice(v any) []map[string]any {
	arr, _ := v.([]any)
	if arr == nil {
		return nil
	}
	out := make([]map[string]any, 0, len(arr))
	for _, item := range arr {
		if m, ok := item.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

func sortedMapKeys(m map[string]any) []string {
	keys := make([]string, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func splitDeltas(s string, size int) []string {
	r := []rune(s)
	if len(r) <= size {
		return []string{s}
	}
	var out []string
	for len(r) > 0 {
		if len(r) <= size {
			out = append(out, string(r))
			break
		}
		out = append(out, string(r[:size]))
		r = r[size:]
	}
	return mergeSmall(out)
}

func mergeSmall(chunks []string) []string {
	if len(chunks) < 2 {
		return chunks
	}
	out := make([]string, 0, len(chunks))
	for _, c := range chunks {
		if len(out) > 0 && len([]rune(c)) < 6 {
			out[len(out)-1] += c
		} else {
			out = append(out, c)
		}
	}
	return out
}

func parseJSON(s string) any {
	var v any
	json.Unmarshal([]byte(s), &v)
	return v
}

// ── unary Chat (kept for backwards compat) ─────────────────────────────

func (s *Simulator) replyText(ctx context.Context, question string, model string) string {
	if model == "" {
		model = s.GatewayModel
	}
	if s.GatewayURL != "" {
		base := strings.TrimRight(s.GatewayURL, "/")
		var err error
		var txt string
		if strings.HasPrefix(strings.ToLower(model), "claude-") {
			txt, err = s.legacyAnthropic(ctx, base, question, model)
		} else {
			txt, err = s.legacyOpenAI(ctx, base, question, model)
		}
		if err == nil && txt != "" {
			return txt
		}
		log.Printf("chat: legacy gateway call failed: %v", err)
	}
	return fmt.Sprintf("这是本地 Augment 模拟后端的回复。你刚才问的是：%q。", question)
}

func (s *Simulator) legacyOpenAI(ctx context.Context, baseURL, question, model string) (string, error) {
	body, _ := json.Marshal(map[string]any{
		"model": model, "messages": []any{map[string]any{"role": "user", "content": question}},
	})
	url := strings.TrimSuffix(baseURL, "/v1")
	url = strings.TrimSuffix(url, "/")
	if !strings.Contains(url, "/chat/completions") {
		url += "/v1/chat/completions"
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if s.GatewayKey != "" {
		req.Header.Set("Authorization", "Bearer "+s.GatewayKey)
	}
	resp, err := s.HTTPClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		Choices []struct {
			Message struct{ Content string }
		} `json:"choices"`
	}
	json.NewDecoder(resp.Body).Decode(&out)
	if len(out.Choices) > 0 {
		return out.Choices[0].Message.Content, nil
	}
	return "", fmt.Errorf("no choices")
}

func (s *Simulator) legacyAnthropic(ctx context.Context, baseURL, question, model string) (string, error) {
	body, _ := json.Marshal(map[string]any{
		"model": model, "max_tokens": 4096,
		"messages": []any{map[string]any{"role": "user", "content": question}},
	})
	url := strings.TrimSuffix(baseURL, "/v1")
	url = strings.TrimSuffix(url, "/")
	if !strings.Contains(url, "/messages") {
		url += "/v1/messages"
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("anthropic-version", "2023-06-01")
	if s.GatewayKey != "" {
		req.Header.Set("x-api-key", s.GatewayKey)
	}
	resp, err := s.HTTPClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	}
	json.NewDecoder(resp.Body).Decode(&out)
	for _, c := range out.Content {
		if c.Type == "text" {
			return c.Text, nil
		}
	}
	return "", fmt.Errorf("no text")
}

// ── conversation-title detection ──────────────────────────────────────

// isTitleRequest detects the hidden chat request the IDE webview sends to
// generate a short conversation title.
func isTitleRequest(userMsg string) bool {
	return strings.Contains(userMsg, "Please provide a clear and concise summary of our conversation") ||
		strings.Contains(userMsg, "less than 6 words")
}

// generateTitle produces a short title for a conversation.  It prefers the
// model gateway when available (unary call), falling back to a deterministic
// title based on the first user message.
func (s *Simulator) generateTitle(ctx context.Context, cfg requestConfig) string {
	if s.GatewayURL != "" && cfg.UserMessage != "" {
		prompt := "Give this coding conversation a short title of at most 6 words. Reply with only the title, no punctuation, no quotes."
		if txt, err := s.legacyOpenAI(ctx, s.GatewayURL, prompt, s.GatewayModel); err == nil && strings.TrimSpace(txt) != "" {
			return truncate(strings.TrimSpace(txt), 60)
		}
		if txt, err := s.legacyAnthropic(ctx, s.GatewayURL, prompt, s.GatewayModel); err == nil && strings.TrimSpace(txt) != "" {
			return truncate(strings.TrimSpace(txt), 60)
		}
	}
	return "New Chat"
}

// ── workspace-questions detection ─────────────────────────────────────

// isWorkspaceQuestionsRequest detects the hidden chat request the IDE webview
// sends to generate onboarding workspace questions.  The exact prompt is
// defined in the plugin's webview JS bundle and is expected to stay stable
// across plugin versions.
func isWorkspaceQuestionsRequest(userMsg string) bool {
	return strings.Contains(userMsg, "Give me the five most important questions") &&
		strings.Contains(userMsg, "separated by a newline")
}

// defaultWorkspaceQuestions returns a short set of onboarding questions.
// When the model gateway can't be reached (or we want to avoid calling it),
// these provide a reasonable fallback.
func defaultWorkspaceQuestions(cfg requestConfig) []string {
	qs := []string{
		"Summarise this project",
		"Explain the architecture of this codebase",
		"What are the key technologies used here?",
		"Find potential bugs in recently changed files",
		"How do I build and run this project?",
	}
	// Prepend a workspace-aware question when we know the root folder.
	if len(cfg.WorkspaceFolders) > 0 {
		if root, _ := cfg.WorkspaceFolders[0]["folder_root"].(string); root != "" {
			// Derive a short project name from the last path segment.
			proj := root
			if idx := strings.LastIndex(root, "/"); idx >= 0 && idx < len(root)-1 {
				proj = root[idx+1:]
			}
			qs = append([]string{
				fmt.Sprintf("What is \"%s\" and how does it work?", proj),
			}, qs...)
		}
	}
	return qs
}

// NodeJSON for unary Chat.
func (s *Simulator) Nodes(question string, nodesOut *[]any) string {
	text := s.replyText(context.Background(), question, s.GatewayModel)
	*nodesOut = []any{
		map[string]any{"id": 1, "type": "THINKING", "content": "Considering the request."},
		map[string]any{"id": 2, "type": "TOOL_USE", "content": "Local mode.",
			"tool_use": map[string]any{
				"tool_use_id": "call-unary", "tool_name": "codebase_retrieval",
				"input_json": fmt.Sprintf(`{"query": %q}`, strings.TrimSpace(question)),
				"is_partial": false,
			}},
		map[string]any{"id": 3, "type": "MAIN_TEXT_FINISHED"},
	}
	return text
}
