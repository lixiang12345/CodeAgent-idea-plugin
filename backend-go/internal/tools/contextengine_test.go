package tools

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

// TestJobProgressParsesSnakeCase locks the contract with ContextEngine's
// index-job payload: progress is persisted with snake_case keys
// (files_total/files_done), which jobProgress must parse into the camelCase
// map the sidecar poller consumes.
func TestJobProgressParsesSnakeCase(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/index-jobs/job-1" {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"job":{"id":"job-1","status":"running","progress":{"phase":"chunk","files_total":656,"files_done":123}}}`))
			return
		}
		http.NotFound(w, r)
	}))
	defer srv.Close()

	c := &ContextEngineClient{URL: srv.URL, HTTP: &http.Client{}}
	c.mu.Lock()
	c.jobID = "job-1"
	c.mu.Unlock()

	p := c.jobProgress()
	if p == nil {
		t.Fatal("jobProgress returned nil")
	}
	if got := p["filesTotal"]; got != 656 {
		t.Errorf("filesTotal = %v, want 656", got)
	}
	if got := p["filesDone"]; got != 123 {
		t.Errorf("filesDone = %v, want 123", got)
	}
	if got := p["phase"]; got != "chunk" {
		t.Errorf("phase = %v, want chunk", got)
	}
}

// TestStatusFiltersByActiveWorkspace verifies Status() reports the state of
// the active workspace only: an unrelated already-indexed project must not
// masquerade as this workspace's stats, and in-flight progress is surfaced
// for the workspace that is actually indexing.
func TestStatusFiltersByActiveWorkspace(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/v1/observability/overview":
			w.Write([]byte(`{"workspaces":[
				{"workspace":{"id":"other-ws"},"indexed":true,"stats":{"fileCount":999,"chunkCount":10,"lastIndexedAt":"2026-08-01T00:00:00Z","indexVersion":1,"hasEmbeddings":false,"embeddingModel":""}},
				{"workspace":{"id":"active-ws"},"indexed":false,"stats":null}
			]}`))
		case "/v1/index-jobs/active-job":
			w.Write([]byte(`{"job":{"id":"active-job","status":"running","progress":{"phase":"scan","files_total":50,"files_done":10}}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := &ContextEngineClient{URL: srv.URL, HTTP: &http.Client{}}
	c.mu.Lock()
	c.workspaceID = "active-ws"
	c.jobID = "active-job"
	c.mu.Unlock()

	s := c.Status()
	if s["indexed"] != false {
		t.Errorf("indexed = %v, want false", s["indexed"])
	}
	if s["stats"] != nil {
		t.Errorf("stats = %v, want nil (must not borrow other workspace's stats)", s["stats"])
	}
	pr, ok := s["progress"].(map[string]any)
	if !ok {
		t.Fatalf("progress missing from status: %#v", s)
	}
	if pr["filesTotal"] != 50 {
		t.Errorf("progress filesTotal = %v, want 50", pr["filesTotal"])
	}

	// When the active workspace is itself indexed, its own stats are reported.
	c2 := &ContextEngineClient{URL: srv.URL, HTTP: &http.Client{}}
	c2.mu.Lock()
	c2.workspaceID = "other-ws"
	c2.mu.Unlock()
	s2 := c2.Status()
	if s2["indexed"] != true {
		t.Errorf("indexed = %v, want true for indexed active workspace", s2["indexed"])
	}
	var round struct {
		Stats struct {
			FileCount int `json:"fileCount"`
		} `json:"stats"`
	}
	b, _ := json.Marshal(s2)
	if err := json.Unmarshal(b, &round); err != nil {
		t.Fatalf("marshal/unmarshal status: %v", err)
	}
	if round.Stats.FileCount != 999 {
		t.Errorf("stats.fileCount = %v, want 999", round.Stats.FileCount)
	}
}

func TestStatusKeepsWorkspaceAndJobSnapshotTogether(t *testing.T) {
	t.Parallel()

	overviewStarted := make(chan struct{})
	releaseOverview := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/v1/observability/overview":
			close(overviewStarted)
			<-releaseOverview
			_, _ = w.Write([]byte(`{"workspaces":[{"workspace":{"id":"workspace-a"},"indexed":false,"stats":null}]}`))
		case "/v1/index-jobs/job-a":
			_, _ = w.Write([]byte(`{"job":{"id":"job-a","status":"running","progress":{"phase":"scan-a","files_total":10,"files_done":4}}}`))
		case "/v1/index-jobs/job-b":
			t.Error("status mixed workspace-a with job-b")
			_, _ = w.Write([]byte(`{"job":{"id":"job-b","status":"running","progress":{"phase":"scan-b"}}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := &ContextEngineClient{URL: srv.URL, HTTP: srv.Client()}
	c.mu.Lock()
	c.activeName = "project-a"
	c.activeLocalRoot = "/host/project-a"
	c.workspaceID = "workspace-a"
	c.jobID = "job-a"
	c.mu.Unlock()

	result := make(chan map[string]any, 1)
	go func() { result <- c.Status() }()
	<-overviewStarted
	c.SetActive("/host/project-b")
	c.mu.Lock()
	c.workspaceID = "workspace-b"
	c.jobID = "job-b"
	c.mu.Unlock()
	close(releaseOverview)

	status := <-result
	progress, ok := status["progress"].(map[string]any)
	if !ok || progress["phase"] != "scan-a" {
		t.Fatalf("status progress = %#v, want workspace-a/job-a snapshot", status["progress"])
	}
}

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
	// A lexical prefix is not a path boundary: /Users/jim must not match
	// /Users/jiming, otherwise a project can be mapped to a bogus /hosting root.
	c.HostBase = "/Users/jim"
	c.SetActive("/Users/jiming/Baz")
	c.mu.Lock()
	root = c.activeLocalRoot
	c.mu.Unlock()
	if root != "/Users/jiming/Baz" {
		t.Errorf("boundary mapping root = %q, want unchanged /Users/jiming/Baz", root)
	}
}

func TestPerConversationWorkspace(t *testing.T) {
	e := New("")
	// This unit test verifies conversation routing only. Disable background HTTP
	// indexing so asynchronous network failures cannot change the active project.
	e.ContextEngine.URL = ""
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

func TestRetrieveForKeepsConcurrentWorkspacesIsolated(t *testing.T) {
	t.Parallel()

	var requestMu sync.Mutex
	var contextPaths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/workspaces":
			_, _ = w.Write([]byte(`{"workspaces":[
				{"id":"workspace-a","name":"project-a","source_mode":"local","local_root":"/host/project-a"},
				{"id":"workspace-b","name":"project-b","source_mode":"local","local_root":"/host/project-b"}
			]}`))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/index-jobs"):
			workspaceID := strings.Split(r.URL.Path, "/")[3]
			_, _ = w.Write([]byte(`{"job":{"id":"job-` + workspaceID + `","status":"succeeded"}}`))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/context"):
			workspaceID := strings.Split(r.URL.Path, "/")[3]
			requestMu.Lock()
			contextPaths = append(contextPaths, r.URL.Path)
			requestMu.Unlock()
			_, _ = w.Write([]byte(`{"packed_text":"sentinel-` + workspaceID + `"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := &ContextEngineClient{URL: srv.URL, HTTP: srv.Client()}
	for iteration := 0; iteration < 20; iteration++ {
		start := make(chan struct{})
		var wg sync.WaitGroup
		wg.Add(2)
		results := make([]string, 2)
		errs := make([]error, 2)
		go func() {
			defer wg.Done()
			<-start
			results[0], errs[0] = c.RetrieveFor("/host/project-a", "alpha", time.Second)
		}()
		go func() {
			defer wg.Done()
			<-start
			results[1], errs[1] = c.RetrieveFor("/host/project-b", "beta", time.Second)
		}()
		close(start)
		wg.Wait()
		if errs[0] != nil || errs[1] != nil {
			t.Fatalf("iteration %d errors = %v, %v", iteration, errs[0], errs[1])
		}
		if results[0] != "sentinel-workspace-a" || results[1] != "sentinel-workspace-b" {
			t.Fatalf("iteration %d results = %q, %q", iteration, results[0], results[1])
		}
	}

	requestMu.Lock()
	defer requestMu.Unlock()
	if len(contextPaths) != 40 {
		t.Fatalf("context requests = %d, want 40", len(contextPaths))
	}
}

func TestEnsureIndexedDisambiguatesSameBasename(t *testing.T) {
	t.Parallel()

	var createdName string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/workspaces":
			_, _ = w.Write([]byte(`{"workspaces":[{"id":"other-app","name":"app","source_mode":"local","local_root":"/host/team-a/app"}]}`))
		case r.Method == http.MethodPost && r.URL.Path == "/v1/workspaces":
			var body struct {
				Name string `json:"name"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Errorf("decode create request: %v", err)
			}
			createdName = body.Name
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"workspace":{"id":"team-b-app"}}`))
		case r.Method == http.MethodPost && r.URL.Path == "/v1/workspaces/team-b-app/index-jobs":
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"job":{"id":"team-b-job","status":"succeeded"}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := &ContextEngineClient{URL: srv.URL, HTTP: srv.Client()}
	c.SetActive("/host/team-b/app")
	if err := c.EnsureIndexed(); err != nil {
		t.Fatal(err)
	}
	if createdName == "" || createdName == "app" || !strings.HasPrefix(createdName, "app-") {
		t.Fatalf("created workspace name = %q, want deterministic disambiguated app-*", createdName)
	}
	c.mu.Lock()
	workspaceID := c.workspaceID
	activeName := c.activeName
	c.mu.Unlock()
	if workspaceID != "team-b-app" || activeName != createdName {
		t.Fatalf("resolved workspace = id %q name %q, want team-b-app/%q", workspaceID, activeName, createdName)
	}
}

func TestRefreshWorkspaceStartsNewJobWhenCachedJobIsQueued(t *testing.T) {
	t.Parallel()

	var indexRequests int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/v1/workspaces/workspace-a/index-jobs":
			indexRequests++
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"job":{"id":"refresh-job","status":"pending"}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	c := &ContextEngineClient{URL: srv.URL, HTTP: srv.Client()}
	c.mu.Lock()
	c.activeName = "project-a"
	c.activeLocalRoot = "/host/project-a"
	c.workspaceID = "workspace-a"
	c.jobID = "completed-job"
	c.jobStatus = "queued"
	c.checkedAt = time.Now().Add(-3 * time.Second)
	c.mu.Unlock()

	if err := c.RefreshWorkspace("/host/project-a"); err != nil {
		t.Fatal(err)
	}
	if indexRequests != 1 {
		t.Fatalf("index requests = %d, want a fresh incremental job", indexRequests)
	}
	c.mu.Lock()
	jobID, jobStatus := c.jobID, c.jobStatus
	c.mu.Unlock()
	if jobID != "refresh-job" || jobStatus != "pending" {
		t.Fatalf("job = %q/%q, want refresh-job/pending", jobID, jobStatus)
	}
}
