package tools

// ContextEngine integration: the `codebase-retrieval` tool is proxied to the
// self-hosted ContextEngine HTTP service (PostgreSQL BM25 + symbols + pgvector).
// Workspace lifecycle is idempotent: ensure the workspace exists, trigger an
// incremental index job, and report readiness so the agent doesn't query an
// unindexed codebase.

import (
	"bytes"
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

	mu             sync.Mutex
	activeName     string // workspace name of the currently active project
	activeLocalRoot string // container-local root of the active project
	workspaceID    string // server-assigned UUID for Workspace
	jobID          string
	jobStatus      string // "", "pending", "running", "succeeded", "failed"
	checkedAt      time.Time
}

// SetActive points the client at a specific host project root (e.g.
// /Users/jiming/SomeProject). It derives the ContextEngine workspace name from
// the last path segment and maps the host path into the container mount
// (/Users/jiming → /host) so the indexer can read it.
func (c *ContextEngineClient) SetActive(hostRoot string) {
	if hostRoot == "" {
		return
	}
	name := filepath.Base(strings.TrimRight(hostRoot, "/"))
	if name == "" || name == "." || name == "/" {
		return
	}
	localRoot := hostRoot
	if c.HostBase != "" && strings.HasPrefix(hostRoot, c.HostBase) {
		localRoot = "/host" + strings.TrimPrefix(hostRoot, c.HostBase)
	}
	c.mu.Lock()
	c.activeName = name
	c.activeLocalRoot = localRoot
	// New workspace: reset the resolved id / job state so we re-ensure.
	c.workspaceID = ""
	c.jobID = ""
	c.jobStatus = ""
	c.checkedAt = time.Time{}
	c.mu.Unlock()
	log.Printf("contextengine: active workspace set name=%s root=%s", name, localRoot)
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
				ID   string `json:"id"`
				Name string `json:"name"`
			} `json:"workspaces"`
		}
		_ = json.Unmarshal(body, &list)
		for _, ws := range list.Workspaces {
			if ws.Name == name {
				c.workspaceID = ws.ID
				break
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

// IndexReady reports whether the codebase has finished indexing. It consults
// the latest job status, refreshing it if it's stale.
func (c *ContextEngineClient) IndexReady() (bool, error) {
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

// Status returns the indexing status of the currently active workspace:
// whether it is indexed plus its stats (file count, chunk count, last indexed
// time). Used by the /contextengine/index-status endpoint.
func (c *ContextEngineClient) Status() map[string]any {
	c.mu.Lock()
	wid := c.workspaceID
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
	for _, w := range d.Workspaces {
		if w.Indexed {
			out["indexed"] = true
			out["stats"] = w.Stats
			break
		}
	}
	return out
}

// Retrieve packs task context from ContextEngine and returns the packed_text.
// It is the HTTP twin of the MCP `codebase-retrieval` tool.
func (c *ContextEngineClient) Retrieve(query string) (string, error) {
	body := map[string]any{
		"information_request": query,
		"top_k":               14,
		"subqueries":          true,
		"include_rules":       true,
	}
	resp, raw, err := c.do("POST", "/v1/workspaces/"+c.workspaceID+"/context", body)
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
