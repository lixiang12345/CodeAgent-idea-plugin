// Package tools implements server-side tool execution for the 21 cloud tools
// that have no local IdeTool in the JVM plugin. When the IDE's ToolsManager
// finds no local implementation, it sends "augmentcode/tools/call" to the
// backend via JSON-RPC through the sidecar → gRPC to our tenant surface.
package tools

import (
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// ToolCallRequest mirrors the proto message the IDE sends.
// ToolCallRequest mirrors the proto message the IDE sends.
// Note: the input field may arrive as a raw JSON object or as a JSON-encoded string
// depending on how the sidecar serializes the protobuf Struct type.
type ToolCallRequest struct {
	Name             string          `json:"name"`
	RequestID        string          `json:"request_id"`
	ToolUseID        string          `json:"tool_use_id"`
	Input            json.RawMessage `json:"input"`
	ConversationID   string          `json:"conversation_id"`
	ParentConvID     string          `json:"parent_conversation_id,omitempty"`
	RootConvID       string          `json:"root_conversation_id,omitempty"`
	TurnID           string          `json:"turn_id,omitempty"`
	History          []any           `json:"history,omitempty"`
}

// parseInput normalizes the input field which may be a raw JSON object or a
// JSON-encoded string (double-encoded from proto Struct serialization).
func parseInput(raw json.RawMessage) map[string]any {
	if len(raw) == 0 {
		return map[string]any{}
	}
	// Try as object first.
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err == nil {
		return m
	}
	// Try as JSON string (double-encoded).
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		var m2 map[string]any
		if err := json.Unmarshal([]byte(s), &m2); err == nil {
			return m2
		}
	}
	return map[string]any{}
}

// ToolCallResponse mirrors the proto message the IDE expects.
type ToolCallResponse struct {
	Text    string `json:"text"`
	IsError bool   `json:"is_error"`
}

// Executor runs tools server-side against the real filesystem.
type Executor struct {
	mu       sync.Mutex
	procMgr  *ProcessManager
	workspaceDir string // fallback workspace root
	ContextEngine *ContextEngineClient // optional retrieval backend for codebase-retrieval
}

// EnsureContextEngineIndexed kicks off ContextEngine indexing at startup
// (idempotent). Call it in the background when the backend boots so the
// codebase is ready before the agent first queries it.
func (e *Executor) EnsureContextEngineIndexed() {
	if e.ContextEngine == nil || e.ContextEngine.URL == "" {
		return
	}
	if err := e.ContextEngine.EnsureIndexed(); err != nil {
		log.Printf("tools: contextengine ensure-indexed (startup): %v", err)
	}
}

// ProcessManager tracks launched background processes.
type ProcessManager struct {
	mu       sync.Mutex
	procs    map[string]*TrackedProcess
}

type TrackedProcess struct {
	ID      string
	Cmd     *exec.Cmd
	Command string
	Started time.Time
	Stdout  strings.Builder
	Stderr  strings.Builder
	Done    bool
	mu      sync.Mutex
}

func NewProcessManager() *ProcessManager {
	return &ProcessManager{procs: make(map[string]*TrackedProcess)}
}

func New(workspace string) *Executor {
	return &Executor{
		procMgr:       NewProcessManager(),
		workspaceDir:  workspace,
		ContextEngine: NewContextEngineClient(),
	}
}

// Execute dispatches a tool call by name and returns the result.
func (e *Executor) Execute(req *ToolCallRequest) *ToolCallResponse {
	input := parseInput(req.Input)

	// Resolve workspace from conversation context if possible.
	ws := e.workspaceDir
	if ws == "" {
		ws = "."
	}

	log.Printf("tools: executing %s (req=%s)", req.Name, req.RequestID)

	switch req.Name {
	// ── file browsing ──────────────────────────────────────────────
	case "view":
		return e.view(ws, input)
	case "view-range-untruncated":
		return e.viewRangeUntruncated(ws, input)
	case "search-untruncated":
		return e.searchUntruncated(ws, input)

	// ── search ─────────────────────────────────────────────────────
	case "grep-search":
		return e.grepSearch(ws, input)

	// ── file editing ───────────────────────────────────────────────
	case "str-replace-editor":
		return e.strReplaceEditor(ws, input)
	case "save-file":
		return e.saveFile(ws, input)
	case "remove-files":
		return e.removeFiles(ws, input)

	// ── terminal / process ─────────────────────────────────────────
	case "launch-process":
		return e.launchProcess(ws, input)
	case "read-process":
		return e.readProcess(input)
	case "write-process":
		return e.writeProcess(input)
	case "kill-process":
		return e.killProcess(input)
	case "list-processes":
		return e.listProcesses()
	case "read-terminal":
		return e.readTerminal(input)

	// ── web tools ──────────────────────────────────────────────────
	case "web-search":
		return e.webSearch(input)
	case "web-fetch":
		return e.webFetch(input)
	case "open-browser":
		return e.openBrowser(input)

	// ── task management ────────────────────────────────────────────
	case "view_tasklist":
		return e.viewTasklist()
	case "add_tasks":
		return e.addTasks(input)
	case "update_tasks":
		return e.updateTasks(input)
	case "reorganize_tasklist":
		return e.reorganizeTasklist(input)

	// ── diagnostics ────────────────────────────────────────────────
	case "diagnostics":
		return e.diagnostics(ws, input)

	// ── memory ─────────────────────────────────────────────────────
	case "memorize":
		return e.memorize(input)

	// ── codebase retrieval ─────────────────────────────────────────
	case "codebase-retrieval":
		return e.codebaseRetrieval(ws, input)
	case "git-commit-retrieval":
		return e.gitCommitRetrieval(ws, input)

	default:
		return &ToolCallResponse{
			Text:    fmt.Sprintf("unknown tool: %s", req.Name),
			IsError: true,
		}
	}
}

// ── file browsing ──────────────────────────────────────────────────

func (e *Executor) view(ws string, input map[string]any) *ToolCallResponse {
	path := resolvePath(ws, str(input["path"]))
	if path == "" {
		return errResp("path is required")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return errResp("cannot read %s: %v", path, err)
	}
	// Add line numbers.
	lines := strings.Split(string(data), "\n")
	var b strings.Builder
	for i, line := range lines {
		fmt.Fprintf(&b, "%6d\t%s\n", i+1, line)
	}
	return okResp(b.String())
}

func (e *Executor) viewRangeUntruncated(ws string, input map[string]any) *ToolCallResponse {
	path := resolvePath(ws, str(input["path"]))
	start := intVal(input["start_line"])
	end := intVal(input["end_line"])
	if path == "" || start <= 0 || end < start {
		return errResp("path, start_line, and end_line (>= start) are required")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return errResp("cannot read %s: %v", path, err)
	}
	lines := strings.Split(string(data), "\n")
	if start > len(lines) {
		return errResp("start_line %d exceeds file length %d", start, len(lines))
	}
	if end > len(lines) {
		end = len(lines)
	}
	var b strings.Builder
	for i := start - 1; i < end; i++ {
		fmt.Fprintf(&b, "%6d\t%s\n", i+1, lines[i])
	}
	return okResp(b.String())
}

func (e *Executor) searchUntruncated(ws string, input map[string]any) *ToolCallResponse {
	path := resolvePath(ws, str(input["path"]))
	query := str(input["query"])
	ctx := intVal(input["context_lines"])
	if ctx <= 0 {
		ctx = 3
	}
	if path == "" || query == "" {
		return errResp("path and query are required")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return errResp("cannot read %s: %v", path, err)
	}
	re, err := regexp.Compile(query)
	if err != nil {
		// Treat as literal.
		re = regexp.MustCompile(regexp.QuoteMeta(query))
	}
	lines := strings.Split(string(data), "\n")
	var b strings.Builder
	for i, line := range lines {
		if re.MatchString(line) {
			start := i - ctx
			if start < 0 {
				start = 0
			}
			end := i + ctx + 1
			if end > len(lines) {
				end = len(lines)
			}
			for j := start; j < end; j++ {
				marker := "  "
				if j == i {
					marker = ">>"
				}
				fmt.Fprintf(&b, "%s %6d\t%s\n", marker, j+1, lines[j])
			}
			b.WriteString("---\n")
		}
	}
	if b.Len() == 0 {
		return okResp("no matches found")
	}
	return okResp(b.String())
}

// ── search ─────────────────────────────────────────────────────────

func (e *Executor) grepSearch(ws string, input map[string]any) *ToolCallResponse {
	query := str(input["query"])
	if query == "" {
		return errResp("query is required")
	}
	include := str(input["include"])
	if include == "" {
		include = "*"
	}
	exclude := str(input["exclude"])
	caseSensitive := bo(input["case_sensitive"])
	maxResults := intVal(input["max_results"])
	if maxResults <= 0 {
		maxResults = 100
	}

	re, err := compilePattern(query, caseSensitive)
	if err != nil {
		return errResp("invalid pattern: %v", err)
	}

	var results []string
	count := 0
	filepath.WalkDir(ws, func(path string, d fs.DirEntry, err error) error {
		if err != nil || count >= maxResults {
			return nil
		}
		if d.IsDir() {
			base := filepath.Base(path)
			if strings.HasPrefix(base, ".") && base != "." {
				return filepath.SkipDir
			}
			if base == "node_modules" || base == ".git" || base == "target" || base == "build" {
				return filepath.SkipDir
			}
			return nil
		}
		rel, _ := filepath.Rel(ws, path)
		if exclude != "" {
			if m, _ := filepath.Match(exclude, rel); m {
				return nil
			}
		}
		if include != "*" {
			if m, _ := filepath.Match(include, rel); !m {
				return nil
			}
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil
		}
		lines := strings.Split(string(data), "\n")
		for i, line := range lines {
			if count >= maxResults {
				return nil
			}
			if re.MatchString(line) {
				results = append(results, fmt.Sprintf("%s:%d: %s", rel, i+1, strings.TrimSpace(line)))
				count++
			}
		}
		return nil
	})
	return okResp(strings.Join(results, "\n"))
}

// ── file editing ───────────────────────────────────────────────────

func (e *Executor) strReplaceEditor(ws string, input map[string]any) *ToolCallResponse {
	path := resolvePath(ws, str(input["path"]))
	if path == "" {
		return errResp("path is required")
	}
	edits, ok := input["edits"].([]any)
	if !ok {
		return errResp("edits array is required")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return errResp("cannot read %s: %v", path, err)
	}
	content := string(data)
	for i, edit := range edits {
		em, _ := edit.(map[string]any)
		oldStr := str(em["old_string"])
		newStr := str(em["new_string"])
		replaceAll := bo(em["replace_all"])
		if oldStr == "" {
			continue
		}
		count := strings.Count(content, oldStr)
		if count == 0 {
			return errResp("edit %d: old_string not found in file", i)
		}
		if count > 1 && !replaceAll {
			return errResp("edit %d: old_string appears %d times; use replace_all or make it unique", i, count)
		}
		if replaceAll {
			content = strings.ReplaceAll(content, oldStr, newStr)
		} else {
			content = strings.Replace(content, oldStr, newStr, 1)
		}
	}
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		return errResp("cannot write %s: %v", path, err)
	}
	return okResp("file edited: %s (%d edits applied)", path, len(edits))
}

func (e *Executor) saveFile(ws string, input map[string]any) *ToolCallResponse {
	path := resolvePath(ws, str(input["path"]))
	content := str(input["content"])
	if path == "" {
		return errResp("path is required")
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return errResp("cannot create directory %s: %v", dir, err)
	}
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		return errResp("cannot write %s: %v", path, err)
	}
	return okResp("file written: %s (%d bytes)", path, len(content))
}

func (e *Executor) removeFiles(ws string, input map[string]any) *ToolCallResponse {
	paths, _ := input["paths"].([]any)
	var results []string
	for _, p := range paths {
		path := resolvePath(ws, fmt.Sprint(p))
		if path == "" {
			continue
		}
		if err := os.Remove(path); err != nil {
			results = append(results, fmt.Sprintf("FAILED: %s — %v", path, err))
		} else {
			results = append(results, fmt.Sprintf("deleted: %s", path))
		}
	}
	return okResp(strings.Join(results, "\n"))
}

// ── terminal / process ─────────────────────────────────────────────

func (e *Executor) launchProcess(ws string, input map[string]any) *ToolCallResponse {
	command := str(input["command"])
	cwd := str(input["cwd"])
	if cwd == "" {
		cwd = ws
	}
	if command == "" {
		return errResp("command is required")
	}
	timeout := intVal(input["timeout"])

	proc, err := e.procMgr.Launch(command, cwd, timeout)
	if err != nil {
		return errResp("launch failed: %v", err)
	}
	return okResp("process launched: id=%s, command=%q", proc.ID, command)
}

func (e *Executor) readProcess(input map[string]any) *ToolCallResponse {
	pid := str(input["process_id"])
	if pid == "" {
		return errResp("process_id is required")
	}
	out, err := e.procMgr.Read(pid)
	if err != nil {
		return errResp("read process: %v", err)
	}
	return okResp(out)
}

func (e *Executor) writeProcess(input map[string]any) *ToolCallResponse {
	pid := str(input["process_id"])
	text := str(input["input"])
	if pid == "" || text == "" {
		return errResp("process_id and input are required")
	}
	if err := e.procMgr.Write(pid, text); err != nil {
		return errResp("write process: %v", err)
	}
	return okResp("input sent to process %s", pid)
}

func (e *Executor) killProcess(input map[string]any) *ToolCallResponse {
	pid := str(input["process_id"])
	if pid == "" {
		return errResp("process_id is required")
	}
	if err := e.procMgr.Kill(pid); err != nil {
		return errResp("kill process: %v", err)
	}
	return okResp("process %s terminated", pid)
}

func (e *Executor) listProcesses() *ToolCallResponse {
	procs := e.procMgr.List()
	var b strings.Builder
	for _, p := range procs {
		fmt.Fprintf(&b, "id=%s command=%q uptime=%s done=%v\n",
			p.ID, p.Command, time.Since(p.Started).Round(time.Second), p.Done)
	}
	if b.Len() == 0 {
		return okResp("no running processes")
	}
	return okResp(b.String())
}

func (e *Executor) readTerminal(input map[string]any) *ToolCallResponse {
	return e.readProcess(input) // same underlying mechanism
}

// ── web tools ──────────────────────────────────────────────────────

func (e *Executor) webSearch(input map[string]any) *ToolCallResponse {
	query := str(input["query"])
	if query == "" {
		return errResp("query is required")
	}
	return okResp("web-search: query=%q — server-side web search not yet configured. Use web-fetch to read specific URLs, or use grep-search for codebase searches.", query)
}

func (e *Executor) webFetch(input map[string]any) *ToolCallResponse {
	url := str(input["url"])
	if url == "" {
		return errResp("url is required")
	}
	// Use Go net/http instead of curl (curl not available in distroless container).
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return errResp("web-fetch: invalid URL: %v", err)
	}
	req.Header.Set("User-Agent", "Augment-Local/1.0")
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return errResp("web-fetch failed: %v", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 50000))
	if err != nil {
		return errResp("web-fetch read failed: %v", err)
	}
	return okResp(string(body))
}

func (e *Executor) openBrowser(input map[string]any) *ToolCallResponse {
	url := str(input["url"])
	if url == "" {
		return errResp("url is required")
	}
	cmd := exec.Command("open", url)
	if err := cmd.Start(); err != nil {
		return errResp("open browser failed: %v", err)
	}
	return okResp("opened %s in browser", url)
}

// ── task management ────────────────────────────────────────────────

var taskStore = struct {
	mu    sync.Mutex
	tasks []map[string]any
	counter int
}{tasks: make([]map[string]any, 0)}

func (e *Executor) viewTasklist() *ToolCallResponse {
	taskStore.mu.Lock()
	defer taskStore.mu.Unlock()
	if len(taskStore.tasks) == 0 {
		return okResp("no tasks")
	}
	data, _ := json.MarshalIndent(taskStore.tasks, "", "  ")
	return okResp(string(data))
}

func (e *Executor) addTasks(input map[string]any) *ToolCallResponse {
	items, _ := input["tasks"].([]any)
	taskStore.mu.Lock()
	defer taskStore.mu.Unlock()
	var ids []string
	for _, item := range items {
		tm, _ := item.(map[string]any)
		taskStore.counter++
		t := map[string]any{
			"id":          fmt.Sprintf("%d", taskStore.counter),
			"subject":     str(tm["subject"]),
			"description": str(tm["description"]),
			"status":      "pending",
		}
		if bb, ok := tm["blocked_by"]; ok {
			t["blocked_by"] = bb
		}
		taskStore.tasks = append(taskStore.tasks, t)
		ids = append(ids, fmt.Sprintf("%d", taskStore.counter))
	}
	return okResp("tasks added: %s", strings.Join(ids, ", "))
}

func (e *Executor) updateTasks(input map[string]any) *ToolCallResponse {
	updates, _ := input["updates"].([]any)
	taskStore.mu.Lock()
	defer taskStore.mu.Unlock()
	var results []string
	for _, u := range updates {
		um, _ := u.(map[string]any)
		id := str(um["task_id"])
		status := str(um["status"])
		subject := str(um["subject"])
		desc := str(um["description"])
		found := false
		for _, t := range taskStore.tasks {
			if str(t["id"]) == id {
				found = true
				if status != "" {
					t["status"] = status
				}
				if subject != "" {
					t["subject"] = subject
				}
				if desc != "" {
					t["description"] = desc
				}
				results = append(results, fmt.Sprintf("updated task %s", id))
				break
			}
		}
		if !found {
			results = append(results, fmt.Sprintf("task %s not found", id))
		}
	}
	return okResp(strings.Join(results, "\n"))
}

func (e *Executor) reorganizeTasklist(input map[string]any) *ToolCallResponse {
	// For now, just return current state.
	return e.viewTasklist()
}

// ── diagnostics ────────────────────────────────────────────────────

func (e *Executor) diagnostics(ws string, input map[string]any) *ToolCallResponse {
	// Read from go vet / static analysis tools.
	paths, _ := input["paths"].([]any)
	if len(paths) == 0 {
		// Default to all Go files.
		paths = []any{"."}
	}
	var results []string
	for _, p := range paths {
		path := resolvePath(ws, fmt.Sprint(p))
		if _, err := os.Stat(path); err != nil {
			results = append(results, fmt.Sprintf("path not found: %s", path))
			continue
		}
		// Try go vet.
		out, err := exec.Command("go", "vet", path).CombinedOutput()
		if err != nil && len(out) > 0 {
			results = append(results, string(out))
		}
	}
	if len(results) == 0 {
		return okResp("no diagnostics found")
	}
	return okResp(strings.Join(results, "\n"))
}

// ── memory ─────────────────────────────────────────────────────────

type memoryItem struct {
	Content   string    `json:"content"`
	CreatedAt time.Time `json:"created_at"`
}

var memoryStore = struct {
	mu    sync.Mutex
	items []memoryItem
}{}

func (e *Executor) memorize(input map[string]any) *ToolCallResponse {
	content := str(input["content"])
	if content == "" {
		return errResp("content is required")
	}
	memoryStore.mu.Lock()
	memoryStore.items = append(memoryStore.items, memoryItem{
		Content:   content,
		CreatedAt: time.Now(),
	})
	memoryStore.mu.Unlock()
	return okResp("memorized")
}

// ── codebase retrieval ─────────────────────────────────────────────

func (e *Executor) codebaseRetrieval(ws string, input map[string]any) *ToolCallResponse {
	query := str(input["information_request"])
	if query == "" {
		query = str(input["query"])
	}
	if query == "" {
		return errResp("information_request or query is required")
	}

	// Prefer ContextEngine when configured: ensure the workspace is indexed,
	// then proxy the retrieval so the agent gets a real evidence pack.
	if ce := e.ContextEngine; ce != nil && ce.URL != "" {
		if err := ce.EnsureIndexed(); err != nil {
			log.Printf("tools: codebase-retrieval: contextengine ensure: %v", err)
		} else {
			ready, err := ce.IndexReady()
			if err != nil {
				log.Printf("tools: codebase-retrieval: contextengine ready: %v", err)
			} else if !ready {
				return okResp("The codebase is still being indexed by the context engine. Please wait for indexing to finish and try again. Query was: %s", query)
			}
			if packed, rerr := ce.Retrieve(query); rerr == nil && strings.TrimSpace(packed) != "" {
				return okResp("%s", packed)
			} else if rerr != nil {
				log.Printf("tools: codebase-retrieval: contextengine retrieve: %v", rerr)
			}
		}
	}

	// Fallback: grep the codebase for the query terms.
	re, err := compilePattern(query, false)
	var results []string
	if err == nil {
		count := 0
		filepath.WalkDir(ws, func(path string, d fs.DirEntry, err error) error {
			if err != nil || count >= 50 {
				return nil
			}
			if d.IsDir() {
				base := filepath.Base(path)
				if strings.HasPrefix(base, ".") || base == "node_modules" || base == ".git" {
					return filepath.SkipDir
				}
				return nil
			}
			rel, _ := filepath.Rel(ws, path)
			data, rerr := os.ReadFile(path)
			if rerr != nil {
				return nil
			}
			if re.MatchString(string(data)) {
				results = append(results, rel)
				count++
			}
			return nil
		})
	} else {
		// Literal substring search.
		lower := strings.ToLower(query)
		count := 0
		filepath.WalkDir(ws, func(path string, d fs.DirEntry, err error) error {
			if err != nil || count >= 50 {
				return nil
			}
			if d.IsDir() {
				base := filepath.Base(path)
				if strings.HasPrefix(base, ".") || base == "node_modules" || base == ".git" {
					return filepath.SkipDir
				}
				return nil
			}
			rel, _ := filepath.Rel(ws, path)
			data, rerr := os.ReadFile(path)
			if rerr != nil {
				return nil
			}
			if strings.Contains(strings.ToLower(string(data)), lower) {
				results = append(results, rel)
				count++
			}
			return nil
		})
	}
	if len(results) == 0 {
		return okResp("no matching files found for: %s", query)
	}
	return okResp("Files matching %q:\n%s", query, strings.Join(results, "\n"))
}

func (e *Executor) gitCommitRetrieval(ws string, input map[string]any) *ToolCallResponse {
	query := str(input["query"])
	author := str(input["author"])
	limit := intVal(input["limit"])
	if limit <= 0 {
		limit = 20
	}
	args := []string{"log", fmt.Sprintf("-%d", limit), "--oneline", "--no-decorate"}
	if author != "" {
		args = append(args, "--author="+author)
	}
	if query != "" {
		args = append(args, "--grep="+query)
	}
	cmd := exec.Command("git", args...)
	cmd.Dir = ws
	out, err := cmd.CombinedOutput()
	if err != nil {
		return errResp("git log failed: %v\n%s", err, string(out))
	}
	if len(out) == 0 {
		return okResp("no commits found")
	}
	return okResp(string(out))
}

// ── ProcessManager ─────────────────────────────────────────────────

func (pm *ProcessManager) Launch(command, cwd string, timeoutSec int) (*TrackedProcess, error) {
	id := fmt.Sprintf("proc-%d", time.Now().UnixNano())
	cmd := exec.Command("sh", "-c", command)
	cmd.Dir = cwd
	cmd.Stdout = nil // we capture manually
	tp := &TrackedProcess{
		ID:      id,
		Cmd:     cmd,
		Command: command,
		Started: time.Now(),
	}
	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderrPipe, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	pm.mu.Lock()
	pm.procs[id] = tp
	pm.mu.Unlock()

	// Read output in background.
	go func() {
		buf := make([]byte, 4096)
		for {
			n, err := stdoutPipe.Read(buf)
			if n > 0 {
				tp.mu.Lock()
				tp.Stdout.Write(buf[:n])
				tp.mu.Unlock()
			}
			if err != nil {
				break
			}
		}
	}()
	go func() {
		buf := make([]byte, 4096)
		for {
			n, err := stderrPipe.Read(buf)
			if n > 0 {
				tp.mu.Lock()
				tp.Stderr.Write(buf[:n])
				tp.mu.Unlock()
			}
			if err != nil {
				break
			}
		}
	}()

	go func() {
		_ = cmd.Wait()
		tp.mu.Lock()
		tp.Done = true
		tp.mu.Unlock()
	}()

	if timeoutSec > 0 {
		go func() {
			time.Sleep(time.Duration(timeoutSec) * time.Millisecond)
			if !tp.Done {
				_ = cmd.Process.Kill()
			}
		}()
	}

	return tp, nil
}

func (pm *ProcessManager) Read(id string) (string, error) {
	pm.mu.Lock()
	tp, ok := pm.procs[id]
	pm.mu.Unlock()
	if !ok {
		return "", fmt.Errorf("process %s not found", id)
	}
	tp.mu.Lock()
	defer tp.mu.Unlock()
	var b strings.Builder
	b.WriteString(tp.Stdout.String())
	b.WriteString(tp.Stderr.String())
	return b.String(), nil
}

func (pm *ProcessManager) Write(id, input string) error {
	pm.mu.Lock()
	tp, ok := pm.procs[id]
	pm.mu.Unlock()
	if !ok {
		return fmt.Errorf("process %s not found", id)
	}
	if tp.Cmd.Process == nil {
		return fmt.Errorf("process %s has exited", id)
	}
	// For stdin, we'd need the pipe; for now, send via process signal.
	// Simple implementation: write to stdin pipe if available.
	return fmt.Errorf("stdin write not yet implemented for process %s", id)
}

func (pm *ProcessManager) Kill(id string) error {
	pm.mu.Lock()
	tp, ok := pm.procs[id]
	pm.mu.Unlock()
	if !ok {
		return fmt.Errorf("process %s not found", id)
	}
	if tp.Cmd.Process == nil {
		return nil
	}
	return tp.Cmd.Process.Kill()
}

func (pm *ProcessManager) List() []*TrackedProcess {
	pm.mu.Lock()
	defer pm.mu.Unlock()
	var out []*TrackedProcess
	for _, p := range pm.procs {
		out = append(out, p)
	}
	return out
}

// ── helpers ────────────────────────────────────────────────────────

func resolvePath(ws, path string) string {
	if path == "" {
		return ""
	}
	if filepath.IsAbs(path) {
		return path
	}
	return filepath.Join(ws, path)
}

func compilePattern(query string, caseSensitive bool) (*regexp.Regexp, error) {
	if !caseSensitive {
		query = "(?i)" + query
	}
	return regexp.Compile(query)
}

func okResp(format string, args ...any) *ToolCallResponse {
	return &ToolCallResponse{Text: fmt.Sprintf(format, args...)}
}

func errResp(format string, args ...any) *ToolCallResponse {
	return &ToolCallResponse{Text: fmt.Sprintf(format, args...), IsError: true}
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

func bo(v any) bool {
	b, _ := v.(bool)
	return b
}

func intVal(v any) int {
	switch x := v.(type) {
	case float64:
		return int(x)
	case int:
		return x
	case int64:
		return int(x)
	default:
		return 0
	}
}
