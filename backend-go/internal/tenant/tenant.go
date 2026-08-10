// Package tenant assembles the tenant surface: the /api-client/* REST gateway
// (grpc-gateway emulation of public_api.Augment), the connect/gRPC protocol
// mux, the discovery table and health endpoints. Every RPC in the 214-method
// table is explicitly routable; unimplemented methods answer with a loud
// 501 / Unimplemented instead of a silent 404.
package tenant

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"

	"augment-local/internal/chat"
	"augment-local/internal/discovery"
	"augment-local/internal/state"
	"augment-local/internal/surface"
	"augment-local/internal/tools"
)

// Server is the tenant-side HTTP surface (port 8787 by default).
type Server struct {
	TenantURL           string
	Store               *state.Store
	Responder           *surface.Responder
	Chat                *chat.Simulator
	ToolExecutor        *tools.Executor
	pathIndex           map[string]string // grpc-gateway REST path -> RPC method name
	TokenHandler        http.HandlerFunc  // set by main to issue tokens (oidc.Provider)
	ideThreadMu         sync.RWMutex
	ideThreadCounts     map[string]int64
	activeWorkspaceRoot string
}

func New(tenantURL, gatewayURL, gatewayModel string) *Server {
	snapshotPath := os.Getenv("STATE_FILE")
	if snapshotPath == "" {
		snapshotPath = "/app/state/augment-local.json"
	}
	st := state.NewWithSnapshot(snapshotPath)
	te := tools.New("")
	s := &Server{
		TenantURL: tenantURL,
		Store:     st,
		Responder: &surface.Responder{
			Store:        st,
			TenantURL:    tenantURL,
			GatewayURL:   gatewayURL,
			GatewayModel: gatewayModel,
			ToolExecutor: te,
		},
		Chat:            chat.New(st, gatewayURL, gatewayModel),
		ToolExecutor:    te,
		pathIndex:       make(map[string]string),
		ideThreadCounts: make(map[string]int64),
	}
	// Bind each conversation to its host project so ContextEngine indexes and
	// retrieves the right workspace (chat requests carry workspace_folders).
	s.Chat.OnWorkspace = te.SetConversationWorkspace
	s.Chat.OnWorkspaceWrite = te.RefreshConversationWorkspace
	for _, m := range surface.Routes {
		if m.Path != "-" {
			s.pathIndex[m.Path] = m.Name
		}
	}
	return s
}

// Handler builds the fully-assembled mux.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// Health + discovery.
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { s.writeJSON(w, 200, map[string]any{"status": "SERVING"}) })
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) { s.writeJSON(w, 200, map[string]any{"status": "SERVING"}) })
	mux.HandleFunc("/api-client/health", func(w http.ResponseWriter, r *http.Request) { s.writeJSON(w, 200, map[string]any{"status": "SERVING"}) })
	mux.HandleFunc("/api-client/client-discovery", discovery.Handler(s.TenantURL))
	mux.HandleFunc("/client-discovery", discovery.Handler(s.TenantURL))

	// /token — the IDE's AugmentAPI.token(tenantURL, tokenRequest) POSTs here
	// (NOT to the OIDC provider). This is where the real token exchange happens after
	// the OAuth callback returns to the IDE with tenant_url in the redirect.
	if s.TokenHandler != nil {
		mux.HandleFunc("/token", s.TokenHandler)
		mux.HandleFunc("/oauth/token", s.TokenHandler)
	}

	// Codebase overview for the webview Home → Codebase summary pipeline.
	// The sidecar forwards generate-project-overview here with the workspace root.
	mux.HandleFunc("/generate-project-overview", s.handleGenerateProjectOverview)

	// Activate ContextEngine for the currently-open project. Called by the
	// plugin bridge when the user selects/opens a project (Home page), so
	// indexing starts on project open rather than on first chat.
	mux.HandleFunc("/contextengine/activate", s.handleContextEngineActivate)

	// ContextEngine indexing status for the active workspace (indexed? stats).
	mux.HandleFunc("/contextengine/index-status", s.handleContextEngineStatus)
	// The IntelliJ webview cannot use the upstream shared-state protobuf bridge,
	// so it synchronizes its authoritative conversation count here.
	mux.HandleFunc("/contextengine/thread-count", s.handleContextEngineThreadCount)

	// All /api-client/* REST routes dispatch through one handler.
	mux.HandleFunc("/api-client/", s.handleAPIClient)

	// Everything else: connect/gRPC paths (augment.public_api.Augment/Method),
	// bare grpc-gateway REST paths, grpc health, or 404.
	mux.HandleFunc("/", s.handleCatchAll)

	return cors(logRequests(mux))
}

// handleContextEngineActivate points ContextEngine at the project the user
// just opened and kicks off indexing (async, idempotent). Called by the plugin
// bridge from getWorkspaceInfo.
func (s *Server) handleContextEngineActivate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		HostRoot string `json:"host_root"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	if req.HostRoot == "" {
		s.writeJSON(w, 400, map[string]any{"error": "host_root required"})
		return
	}
	log.Printf("tenant: contextengine activate root=%s", req.HostRoot)
	s.setActiveWorkspace(req.HostRoot)
	s.ToolExecutor.SetActiveWorkspace(req.HostRoot)
	go s.ToolExecutor.EnsureContextEngineIndexed()
	s.writeJSON(w, 200, map[string]any{"ok": true})
}

// handleContextEngineStatus reports ContextEngine indexing status for the
// currently active workspace (indexed flag + file/chunk stats).
func (s *Server) handleContextEngineStatus(w http.ResponseWriter, r *http.Request) {
	if s.ToolExecutor == nil || s.ToolExecutor.ContextEngine == nil {
		s.writeJSON(w, 200, map[string]any{
			"indexed":      false,
			"totalThreads": s.workspaceThreadCount(),
			"error":        "contextengine not configured",
		})
		return
	}
	status := s.ToolExecutor.ContextEngine.Status()
	status["totalThreads"] = s.workspaceThreadCount()
	s.writeJSON(w, 200, status)
}

func (s *Server) handleContextEngineThreadCount(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "POST required"})
		return
	}
	if origin := r.Header.Get("Origin"); origin != "" && origin != "http://augment.localhost" {
		s.writeJSON(w, http.StatusForbidden, map[string]any{"error": "origin not allowed"})
		return
	}
	var req struct {
		TotalThreads  int64  `json:"totalThreads"`
		WorkspaceRoot string `json:"workspaceRoot"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req); err != nil {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid thread count"})
		return
	}
	if req.TotalThreads < 0 || req.TotalThreads > 1_000_000 {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"error": "thread count out of range"})
		return
	}
	workspaceRoot := req.WorkspaceRoot
	if workspaceRoot == "" {
		workspaceRoot = r.Header.Get("X-Augment-Workspace")
	}
	workspaceRoot = s.threadCountWorkspace(workspaceRoot)
	s.ideThreadMu.Lock()
	s.ideThreadCounts[workspaceRoot] = req.TotalThreads
	s.ideThreadMu.Unlock()
	s.writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) workspaceThreadCount() int {
	workspaceRoot := s.threadCountWorkspace("")
	s.ideThreadMu.RLock()
	count, ok := s.ideThreadCounts[workspaceRoot]
	s.ideThreadMu.RUnlock()
	if ok {
		return int(count)
	}
	return len(s.Store.ListConversations(workspaceRoot))
}

func (s *Server) setActiveWorkspace(root string) {
	s.ideThreadMu.Lock()
	if root != "" {
		if _, scoped := s.ideThreadCounts[root]; !scoped {
			if legacy, ok := s.ideThreadCounts[""]; ok {
				s.ideThreadCounts[root] = legacy
			}
		}
	}
	s.activeWorkspaceRoot = root
	s.ideThreadMu.Unlock()
}

// threadCountWorkspace resolves an explicit workspace root first and otherwise
// uses the workspace most recently activated by the IDE bridge. The empty key
// preserves compatibility with older webviews that cannot send a root.
func (s *Server) threadCountWorkspace(explicit string) string {
	if explicit != "" {
		return explicit
	}
	s.ideThreadMu.RLock()
	root := s.activeWorkspaceRoot
	s.ideThreadMu.RUnlock()
	return root
}

// logRequests wraps a handler to log every incoming request on the tenant surface.
func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("tenant: %s %s content-type=%s", r.Method, r.URL.Path, r.Header.Get("Content-Type"))
		next.ServeHTTP(w, r)
	})
}

func (s *Server) writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// handleAPIClient serves the /api-client/<grpc-gateway-path> REST surface.
func (s *Server) handleAPIClient(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api-client")
	method := s.pathIndex[path]
	if method == "" {
		s.writeJSON(w, 404, map[string]any{"code": "not_found", "message": fmt.Sprintf("no RPC for path %s", r.URL.Path)})
		return
	}
	s.dispatchREST(w, r, method, path)
}

// dispatchREST runs a method by its grpc-gateway path form.
func (s *Server) dispatchREST(w http.ResponseWriter, r *http.Request, method, path string) {
	if surface.ImplementedStreams[method] {
		s.streamREST(w, r, method)
		return
	}
	req := decodeBody(r)
	resp, handled, err := s.Responder.Handle(method, req)
	if err != nil {
		s.writeJSON(w, 500, map[string]any{"code": "internal", "message": err.Error()})
		return
	}
	if !handled {
		log.Printf("tenant: %s -> 501 surface not implemented (path %s)", method, path)
		s.writeJSON(w, 501, map[string]any{"code": "unimplemented", "message": fmt.Sprintf("surface not implemented: %s", method)})
		return
	}
	s.writeJSON(w, 200, resp)
}

// streamREST serves the ChatStream simulator over NDJSON or SSE depending on
// the Accept header.
func (s *Server) streamREST(w http.ResponseWriter, r *http.Request, method string) {
	req := decodeBody(r)
	flow := chat.FlowNDJSON
	if strings.Contains(r.Header.Get("Accept"), "text/event-stream") {
		flow = chat.FlowSSE
		w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")
	} else {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
	}
	w.WriteHeader(http.StatusOK)
	if err := s.Chat.Stream(r.Context(), w, flow, req); err != nil {
		log.Printf("tenant: chat stream error: %v", err)
	}
}

// handleCatchAll dispatches connect/gRPC RPC paths, bare REST paths, and 404s.
func (s *Server) handleCatchAll(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path

	// Bare grpc-gateway REST route (no /api-client prefix).
	if method, ok := s.pathIndex[path]; ok {
		s.dispatchREST(w, r, method, path)
		return
	}

	// grpc.health.v1.Health/Check over gRPC/connect.
	if path == "/grpc.health.v1.Health/Check" || path == "/grpc.health.v1.Health/CheckStream" {
		s.handleGRPCHealth(w, r)
		return
	}

	// connect/gRPC style: /<service>/<method> where service contains a dot,
	// or known dotless services (e.g. "augmentcode" for tools/hooks RPCs).
	trimmed := strings.TrimPrefix(path, "/")
	slash := strings.Index(trimmed, "/")
	if slash > 0 {
		svc := trimmed[:slash]
		method := trimmed[slash+1:]
		if method != "" && (strings.Contains(svc, ".") || isKnownDotlessService(svc)) {
			s.handleRPC(w, r, svc, method)
			return
		}
	}

	s.writeJSON(w, 404, map[string]any{"code": "not_found", "message": fmt.Sprintf("unknown path %s", path)})
}

// decodeBody reads a JSON request body into a map (empty body -> empty map).
func decodeBody(r *http.Request) map[string]any {
	req := map[string]any{}
	body, err := io.ReadAll(r.Body)
	if err != nil || len(strings.TrimSpace(string(body))) == 0 {
		return req
	}
	if err := json.Unmarshal(body, &req); err != nil {
		// A single JSON value at top level (e.g. an array or string) is
		// tolerated as an empty request — the message shape we care about is
		// an object.
		var v any
		if err2 := json.Unmarshal(body, &v); err2 == nil {
			req["_raw"] = v
		}
	}
	return req
}

// isKnownDotlessService allows gRPC service names that don't contain a dot.
// The IDE sidecar uses service names like "augmentcode" for tools/hooks RPCs
// that are routed through its JSON-RPC bridge to the backend.
func isKnownDotlessService(svc string) bool {
	switch svc {
	case "augmentcode":
		return true
	default:
		return false
	}
}

// cors wraps a handler with permissive CORS for the IDE webview.
func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Access-Control-Allow-Origin", "*")
		h.Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
		h.Set("Access-Control-Allow-Headers", "Authorization, Content-Type, Connect-Protocol-Version, Grpc-Timeout, X-Request-Id, Accept, Accept-Encoding")
		h.Set("Access-Control-Expose-Headers", "Grpc-Status, Grpc-Message, Connect-Protocol-Version")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}
