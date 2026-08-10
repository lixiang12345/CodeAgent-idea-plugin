package tools

// ContextEngine integration: the `codebase-retrieval` tool is proxied to the
// self-hosted ContextEngine HTTP service (PostgreSQL BM25 + symbols + pgvector).
// Workspace lifecycle is idempotent: ensure the workspace exists, trigger an
// incremental index job, and report readiness so the agent doesn't query an
// unindexed codebase.

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

// ContextEngineClient talks to the ContextEngine HTTP API.
type ContextEngineClient struct {
	URL       string
	Key       string
	Workspace string // fallback workspace name (when no active workspace is known)
	LocalRoot string // fallback local_root (when no active workspace is known)
	HostBase  string // host mount base mapped to /host in the container
	HTTP      *http.Client

	operationMu     sync.Mutex
	mu              sync.Mutex
	activeName      string // workspace name of the currently active project
	activeLocalRoot string // container-local root of the active project
	workspaceID     string // server-assigned UUID for Workspace
	jobID           string
	jobStatus       string // "", "pending", "running", "succeeded", "failed"
	checkedAt       time.Time
	workspaceStates map[string]contextEngineWorkspaceState
}

type contextEngineWorkspaceState struct {
	name        string
	localRoot   string
	workspaceID string
	jobID       string
	jobStatus   string
	checkedAt   time.Time
}

// SetActive points the client at a specific host project root (e.g.
// /Users/jiming/SomeProject). It derives the ContextEngine workspace name from
// the last path segment and maps the host path into the container mount
// (/Users/jiming → /host) so the indexer can read it.
func (c *ContextEngineClient) SetActive(hostRoot string) {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	c.setActive(hostRoot)
}

func (c *ContextEngineClient) setActive(hostRoot string) {
	if hostRoot == "" {
		return
	}
	name := filepath.Base(strings.TrimRight(hostRoot, "/"))
	if name == "" || name == "." || name == "/" {
		return
	}
	localRoot := hostRoot
	if mappedRoot, ok := mapHostWorkspaceRoot(c.HostBase, hostRoot); ok {
		localRoot = mappedRoot
	}
	c.mu.Lock()
	if c.activeLocalRoot == localRoot {
		c.mu.Unlock()
		return
	}
	c.saveActiveStateLocked()
	c.activeName = name
	c.activeLocalRoot = localRoot
	if cached, ok := c.workspaceStates[localRoot]; ok {
		c.activeName = cached.name
		c.workspaceID = cached.workspaceID
		c.jobID = cached.jobID
		c.jobStatus = cached.jobStatus
		c.checkedAt = cached.checkedAt
	} else {
		c.workspaceID = ""
		c.jobID = ""
		c.jobStatus = ""
		c.checkedAt = time.Time{}
	}
	c.mu.Unlock()
	log.Printf("contextengine: active workspace set name=%s root=%s", name, localRoot)
}

// mapHostWorkspaceRoot maps a workspace below the configured host mount base
// to the path shared by the backend and ContextEngine containers. filepath.Rel
// provides a path-boundary check, so similar lexical prefixes cannot escape
// the configured mount.
func mapHostWorkspaceRoot(hostBase, hostRoot string) (string, bool) {
	if hostBase == "" || hostRoot == "" || !filepath.IsAbs(hostBase) || !filepath.IsAbs(hostRoot) {
		return "", false
	}
	base := filepath.Clean(hostBase)
	root := filepath.Clean(hostRoot)
	relative, err := filepath.Rel(base, root)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", false
	}
	return filepath.Join("/host", relative), true
}

func (c *ContextEngineClient) saveActiveStateLocked() {
	if c.activeLocalRoot == "" {
		return
	}
	if c.workspaceStates == nil {
		c.workspaceStates = make(map[string]contextEngineWorkspaceState)
	}
	c.workspaceStates[c.activeLocalRoot] = contextEngineWorkspaceState{
		name:        c.activeName,
		localRoot:   c.activeLocalRoot,
		workspaceID: c.workspaceID,
		jobID:       c.jobID,
		jobStatus:   c.jobStatus,
		checkedAt:   c.checkedAt,
	}
}

// NewContextEngineClient builds a client from environment variables.
// CONTEXTENGINE_URL default: http://contextengine:8787 (docker network).
func NewContextEngineClient() *ContextEngineClient {
	url := os.Getenv("CONTEXTENGINE_URL")
	if url == "" {
		url = "http://contextengine:8787"
	}
	ws := os.Getenv("CONTEXTENGINE_WORKSPACE")
	if ws == "" {
		ws = "local"
	}
	return &ContextEngineClient{
		URL:       strings.TrimRight(url, "/"),
		Key:       os.Getenv("CONTEXTENGINE_API_KEY"),
		Workspace: ws,
		LocalRoot: os.Getenv("CONTEXTENGINE_LOCAL_ROOT"),
		HostBase:  os.Getenv("CONTEXTENGINE_HOST_BASE"),
		HTTP:      &http.Client{Timeout: 45 * time.Second},
	}
}

func (c *ContextEngineClient) do(method, path string, body any) (*http.Response, []byte, error) {
	var rdr *bytes.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, nil, err
		}
		rdr = bytes.NewReader(b)
	} else {
		rdr = bytes.NewReader(nil)
	}
	req, err := http.NewRequest(method, c.URL+path, rdr)
	if err != nil {
		return nil, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.Key != "" {
		req.Header.Set("Authorization", "Bearer "+c.Key)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	buf := make([]byte, 0, 4096)
	tmp := make([]byte, 4096)
	for {
		n, err := resp.Body.Read(tmp)
		buf = append(buf, tmp[:n]...)
		if err != nil {
			break
		}
	}
	return resp, buf, nil
}

// EnsureIndexed makes sure the active workspace exists and an index job has
// been kicked off. It is idempotent: a succeeded/complete workspace is left
// alone. The workspace name/root come from SetActive (the open project); the
// fallback fields (Workspace/LocalRoot) are used only before any project is
// known.
func (c *ContextEngineClient) EnsureIndexed() error {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	return c.ensureIndexed()
}

func (c *ContextEngineClient) ensureIndexed() error {
	if c.URL == "" {
		return fmt.Errorf("ContextEngine URL not configured")
	}
	c.mu.Lock()
	defer c.mu.Unlock()

	name := c.activeName
	root := c.activeLocalRoot
	if name == "" {
		name = c.Workspace
	}
	if root == "" {
		root = c.LocalRoot
	}
	if name == "" {
		return fmt.Errorf("contextengine: no workspace name (set CONTEXTENGINE_WORKSPACE or an active workspace)")
	}

	// 1) Resolve the workspace id: list workspaces and find one whose name
	// matches, otherwise create it (id is server-assigned UUID).
	if c.workspaceID == "" {
		resp, body, err := c.do("GET", "/v1/workspaces", nil)
		if err != nil {
			return fmt.Errorf("contextengine list workspaces: %w", err)
		}
		if resp.StatusCode != http.StatusOK {
			return fmt.Errorf("contextengine list workspaces: status %d: %s", resp.StatusCode, truncate(string(body), 200))
		}
		var list struct {
			Workspaces []struct {
				ID        string `json:"id"`
				Name      string `json:"name"`
				LocalRoot string `json:"local_root"`
			} `json:"workspaces"`
		}
		_ = json.Unmarshal(body, &list)
		// Prefer the source root over the display name. Two repositories can
		// legitimately share a basename (for example two checkouts named app).
		for _, ws := range list.Workspaces {
			if root != "" && ws.LocalRoot == root {
				c.workspaceID = ws.ID
				name = ws.Name
				c.activeName = ws.Name
				break
			}
		}
		nameConflict := false
		for _, ws := range list.Workspaces {
			if c.workspaceID != "" {
				break
			}
			if ws.Name == name {
				if root == "" || ws.LocalRoot == "" || ws.LocalRoot == root {
					c.workspaceID = ws.ID
					break
				}
				nameConflict = true
			}
		}
		if c.workspaceID == "" && nameConflict {
			name = disambiguatedWorkspaceName(name, root)
			c.activeName = name
			for _, ws := range list.Workspaces {
				if ws.Name == name && (ws.LocalRoot == "" || ws.LocalRoot == root) {
					c.workspaceID = ws.ID
					break
				}
			}
		}
	}
	if c.workspaceID == "" {
		if root == "" {
			return fmt.Errorf("contextengine: workspace %q missing and no local root known", name)
		}
		create := map[string]any{
			"name":        name,
			"source_mode": "local",
			"local_root":  root,
		}
		resp2, body2, err := c.do("POST", "/v1/workspaces", create)
		if err != nil {
			return fmt.Errorf("contextengine create workspace: %w", err)
		}
		if resp2.StatusCode != http.StatusCreated && resp2.StatusCode != http.StatusConflict {
			return fmt.Errorf("contextengine create workspace: status %d: %s", resp2.StatusCode, truncate(string(body2), 200))
		}
		if resp2.StatusCode == http.StatusCreated {
			var created struct {
				Workspace struct {
					ID string `json:"id"`
				} `json:"workspace"`
			}
			if err := json.Unmarshal(body2, &created); err == nil && created.Workspace.ID != "" {
				c.workspaceID = created.Workspace.ID
			}
		}
	}
	if c.workspaceID == "" {
		return fmt.Errorf("contextengine: could not resolve or create workspace %q", name)
	}

	// 2) Check for a recent, non-terminal job before scheduling a new one.
	if c.jobStatus == "succeeded" {
		return nil
	}
	inFlight := c.jobStatus == "queued" || c.jobStatus == "pending" || c.jobStatus == "running"
	if c.jobID != "" && inFlight && time.Since(c.checkedAt) < time.Minute {
		return nil
	}

	// 3) Trigger an incremental index job.
	resp3, body3, err := c.do("POST", "/v1/workspaces/"+c.workspaceID+"/index-jobs", map[string]any{"mode": "incremental"})
	if err != nil {
		return fmt.Errorf("contextengine index-jobs: %w", err)
	}
	if resp3.StatusCode != http.StatusAccepted && resp3.StatusCode != http.StatusOK {
		return fmt.Errorf("contextengine index-jobs: status %d: %s", resp3.StatusCode, truncate(string(body3), 200))
	}
	var job struct {
		Job struct {
			ID     string `json:"id"`
			Status string `json:"status"`
		} `json:"job"`
	}
	_ = json.Unmarshal(body3, &job)
	if job.Job.ID != "" {
		c.jobID = job.Job.ID
		c.jobStatus = job.Job.Status
	}
	c.checkedAt = time.Now()
	log.Printf("contextengine: index job %s status=%s", c.jobID, c.jobStatus)
	return nil
}

func disambiguatedWorkspaceName(name, root string) string {
	sum := sha256.Sum256([]byte(filepath.Clean(root)))
	return fmt.Sprintf("%s-%x", name, sum[:4])
}

// IndexReady reports whether the codebase has finished indexing. It consults
// the latest job status, refreshing it if it's stale.
func (c *ContextEngineClient) IndexReady() (bool, error) {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	return c.indexReady()
}

func (c *ContextEngineClient) indexReady() (bool, error) {
	c.mu.Lock()
	jobID := c.jobID
	status := c.jobStatus
	staleness := time.Since(c.checkedAt)
	c.mu.Unlock()

	if jobID == "" {
		return false, nil
	}
	if status == "succeeded" {
		return true, nil
	}
	if status == "failed" {
		return false, fmt.Errorf("contextengine index job %s failed", jobID)
	}
	inFlight := status == "queued" || status == "pending" || status == "running"
	// Terminal-but-unknown statuses or very recent checks: avoid hammering.
	if staleness < 2*time.Second {
		return false, nil
	}
	if !inFlight && status != "" && staleness < time.Minute {
		return false, nil
	}

	// Refresh from the job endpoint.
	resp, body, err := c.do("GET", "/v1/index-jobs/"+jobID, nil)
	if err != nil {
		return false, err
	}
	if resp.StatusCode == http.StatusNotFound {
		return false, nil
	}
	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("contextengine job status: %d", resp.StatusCode)
	}
	var out struct {
		Job struct {
			Status string `json:"status"`
		} `json:"job"`
	}
	_ = json.Unmarshal(body, &out)
	c.mu.Lock()
	c.jobStatus = out.Job.Status
	c.checkedAt = time.Now()
	c.mu.Unlock()
	return out.Job.Status == "succeeded", nil
}

// WaitIndexReady polls IndexReady until the workspace is indexed or maxWait
// elapses, so callers can wait out the initial indexing instead of returning
// "still indexing" immediately.
func (c *ContextEngineClient) WaitIndexReady(maxWait time.Duration) bool {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	return c.waitIndexReady(maxWait)
}

func (c *ContextEngineClient) waitIndexReady(maxWait time.Duration) bool {
	deadline := time.Now().Add(maxWait)
	for {
		ready, err := c.indexReady()
		if err == nil && ready {
			return true
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return false
		}
		delay := 2 * time.Second
		if remaining < delay {
			delay = remaining
		}
		time.Sleep(delay)
	}
}

// Status returns the indexing status of the currently active workspace:
// whether it is indexed plus its stats (file count, chunk count, last indexed
// time). Used by the /contextengine/index-status endpoint.
func (c *ContextEngineClient) Status() map[string]any {
	c.mu.Lock()
	wid := c.workspaceID
	jobID := c.jobID
	name := c.activeName
	if name == "" {
		name = c.Workspace
	}
	root := c.activeLocalRoot
	if root == "" {
		root = c.LocalRoot
	}
	c.mu.Unlock()

	out := map[string]any{
		"workspace": name,
		"root":      root,
		"indexed":   false,
		"stats":     nil,
	}
	if wid == "" {
		return out
	}
	// The per-workspace GET doesn't include index status; read it from the
	// observability overview which lists workspaces with indexed + stats.
	resp, body, err := c.do("GET", "/v1/observability/overview", nil)
	if err != nil || resp.StatusCode != http.StatusOK {
		return out
	}
	var d struct {
		Workspaces []struct {
			Workspace struct {
				ID string `json:"id"`
			} `json:"workspace"`
			Indexed bool `json:"indexed"`
			Stats   struct {
				FileCount      int    `json:"fileCount"`
				ChunkCount     int    `json:"chunkCount"`
				LastIndexedAt  string `json:"lastIndexedAt"`
				IndexVersion   int    `json:"indexVersion"`
				HasEmbeddings  bool   `json:"hasEmbeddings"`
				EmbeddingModel string `json:"embeddingModel"`
			} `json:"stats"`
		} `json:"workspaces"`
	}
	if err := json.Unmarshal(body, &d); err != nil {
		return out
	}
	// Match the active workspace specifically: a previously-indexed project
	// must not masquerade as the status of the workspace that is indexing now.
	for _, w := range d.Workspaces {
		if w.Workspace.ID != wid {
			continue
		}
		if w.Indexed {
			out["indexed"] = true
			out["stats"] = w.Stats
			return out
		}
		// Not indexed yet — surface the in-flight job progress so the UI can
		// render a real percentage (filesDone/filesTotal).
		if p := c.jobProgressFor(jobID); p != nil {
			out["progress"] = p
		}
		return out
	}
	// Active workspace not present in the overview yet (job just kicked off,
	// stats not materialized). Fall back to the live job if one is running.
	if p := c.jobProgressFor(jobID); p != nil {
		out["progress"] = p
	}
	return out
}

// jobProgress returns the in-progress index job's scan/chunk progress, or nil
// when there's no live job (completed jobs are pruned by ContextEngine).
func (c *ContextEngineClient) jobProgress() map[string]any {
	c.mu.Lock()
	jobID := c.jobID
	c.mu.Unlock()
	return c.jobProgressFor(jobID)
}

func (c *ContextEngineClient) jobProgressFor(jobID string) map[string]any {
	if jobID == "" {
		return nil
	}
	resp, body, err := c.do("GET", "/v1/index-jobs/"+jobID, nil)
	if err != nil || resp.StatusCode != http.StatusOK {
		return nil
	}
	var out struct {
		Job struct {
			Status   string `json:"status"`
			Progress struct {
				Phase      string `json:"phase"`
				FilesTotal int    `json:"files_total"`
				FilesDone  int    `json:"files_done"`
			} `json:"progress"`
		} `json:"job"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return nil
	}
	return map[string]any{
		"status":     out.Job.Status,
		"phase":      out.Job.Progress.Phase,
		"filesTotal": out.Job.Progress.FilesTotal,
		"filesDone":  out.Job.Progress.FilesDone,
	}
}

// Retrieve packs task context from ContextEngine and returns the packed_text.
// It is the HTTP twin of the MCP `codebase-retrieval` tool.
func (c *ContextEngineClient) Retrieve(query string) (string, error) {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	return c.retrieve(query)
}

func (c *ContextEngineClient) retrieve(query string) (string, error) {
	c.mu.Lock()
	workspaceID := c.workspaceID
	c.mu.Unlock()
	if workspaceID == "" {
		return "", fmt.Errorf("contextengine: no resolved workspace")
	}
	body := map[string]any{
		"information_request": query,
		"top_k":               14,
		"subqueries":          true,
		"include_rules":       true,
	}
	resp, raw, err := c.do("POST", "/v1/workspaces/"+workspaceID+"/context", body)
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("contextengine context: status %d: %s", resp.StatusCode, truncate(string(raw), 200))
	}
	var out struct {
		PackedText string `json:"packed_text"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", err
	}
	return out.PackedText, nil
}

// RetrieveFor performs the full workspace switch, index readiness check and
// context query as one operation. Serializing this sequence prevents another
// project from replacing workspaceID between EnsureIndexed and Retrieve.
func (c *ContextEngineClient) RetrieveFor(hostRoot, query string, maxWait time.Duration) (string, error) {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	if hostRoot != "" {
		c.setActive(hostRoot)
	}
	if err := c.ensureIndexed(); err != nil {
		return "", err
	}
	if !c.waitIndexReady(maxWait) {
		return "", fmt.Errorf("contextengine: active workspace index is not ready")
	}
	return c.retrieve(query)
}

// EnsureWorkspace indexes the specified project without allowing a concurrent
// activation to redirect the index job to a different workspace.
func (c *ContextEngineClient) EnsureWorkspace(hostRoot string) error {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	c.setActive(hostRoot)
	return c.ensureIndexed()
}

// RefreshWorkspace schedules a new incremental index after a write.
// EnsureWorkspace skips a workspace whose latest job already succeeded, which
// is correct during activation but would otherwise leave changed files stale.
func (c *ContextEngineClient) RefreshWorkspace(hostRoot string) error {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	if hostRoot != "" {
		c.setActive(hostRoot)
	}
	c.mu.Lock()
	// A write can land while the previous job is still queued or running. Queue
	// another incremental pass in that case so the completed index cannot miss
	// the new file contents.
	c.jobID = ""
	c.jobStatus = ""
	c.checkedAt = time.Time{}
	c.mu.Unlock()
	return c.ensureIndexed()
}
