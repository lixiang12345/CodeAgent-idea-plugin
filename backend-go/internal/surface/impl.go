// Package surface maps the 214-method public_api.Augment RPC table to a small
// set of working handlers plus an explicit Unimplemented registry. Response
// maps use proto3 JSON field names so connect+json / grpc-gateway clients see
// the same wire shape the real backend serves.
package surface

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"augment-local/internal/state"
	"augment-local/internal/tools"
)

// Responder carries tenant state into the implemented handlers.
type Responder struct {
	Store *state.Store
	// TenantURL is echoed into responses that embed it (e.g. SaveChat urls).
	TenantURL string
	// GatewayURL, when set, forwards ChatStream requests to an OpenAI-compatible
	// upstream and splices its reply into the simulated node sequence.
	GatewayURL string
	// GatewayModel is the fallback model for lightweight unary model calls.
	GatewayModel string
	// HTTPClient is injectable for gateway tests. A short-timeout client is used
	// when nil so inline completion never stalls the IDE input loop.
	HTTPClient *http.Client
	// ToolExecutor executes remote tool calls on behalf of the IDE.
	ToolExecutor *tools.Executor
}

// Implemented is the set of RPC names with a real handler.
var Implemented = map[string]func(*Responder, map[string]any) (any, error){
	"GetModels":                  (*Responder).getModels,
	"GetCreditInfo":              (*Responder).getCreditInfo,
	"Completion":                 (*Responder).completion,
	"ResolveCompletionsRpc":      (*Responder).resolveCompletions,
	"ChatInputCompletion":        (*Responder).chatInputCompletion,
	"ResolveChatInputCompletion": emptyOK,
	"ListRemoteTools":            (*Responder).listRemoteTools,
	"CheckToolSafety":            (*Responder).checkToolSafety,
	"RunRemoteTool":              (*Responder).runRemoteTool,
	"CodebaseRetrieval":          (*Responder).codebaseRetrieval,
	"CodebaseRetrievalRaw":       (*Responder).codebaseRetrievalRaw,
	"ListRemoteAgents":           (*Responder).listRemoteAgents,
	"CreateConversation":         (*Responder).createConversation,
	"GetConversation":            (*Responder).getConversation,
	"UpdateConversation":         (*Responder).updateConversation,
	"ListConversations":          (*Responder).listConversations,
	"ListChatHistory":            (*Responder).listChatHistory,
	"CountChatHistory":           (*Responder).countChatHistory,
	"InsertChatHistory":          (*Responder).insertChatHistory,
	"SaveChat":                   (*Responder).saveChat,
	"GetSubscriptionInfo":        (*Responder).getSubscriptionInfo,
	"GetSubscriptionBanner":      emptyOK,
	"ReadNotifications":          emptyOK,
	"IsUserGithubConfigured":     func(_ *Responder, _ map[string]any) (any, error) { return map[string]any{"is_configured": false}, nil },
	"ListGithubReposForAuthenticatedUser": func(_ *Responder, _ map[string]any) (any, error) {
		return map[string]any{"repos": []any{}, "has_next_page": false}, nil
	},
	"ListGithubRepoBranches": func(_ *Responder, _ map[string]any) (any, error) {
		return map[string]any{"branches": []any{}}, nil
	},
	"RecordRequestEvents":                recordEvents("request"),
	"RecordSessionEvents":                recordEvents("session"),
	"ReportClientMetrics":                emptyOK,
	"ReportFeatureVector":                emptyOK,
	"SendChatFeedback":                   emptyOK,
	"SendCompletionFeedback":             emptyOK,
	"GetMcpUserSettings":                 emptyOK,
	"GetMcpTenantSettings":               emptyOK,
	"GetMcpUserConfigs":                  emptyOK,
	"GetMcpTenantConfigs":                emptyOK,
	"GetMcpConfigById":                   emptyOK,
	"RemoveMcpUserConfig":                emptyOK,
	"RemoveMcpTenantConfig":              emptyOK,
	"UpsertMcpUserConfig":                emptyOK,
	"UpsertMcpTenantConfig":              emptyOK,
	"GetTenantToolPermissions":           emptyOK,
	"ListAgentPersonas":                  emptyOK,
	"GetAgentPersona":                    emptyOK,
	"ListAgentCapabilities":              emptyOK,
	"ListCanvases":                       emptyOK,
	"UpsertUserSecret":                   emptyOK,
	"GetUserSecret":                      emptyOK,
	"ListUserSecrets":                    emptyOK,
	"DeleteUserSecret":                   emptyOK,
	"GetUserSecrets":                     emptyOK,
	"GetTenantSecret":                    emptyOK,
	"GetTenantSecrets":                   emptyOK,
	"ListTenantSecrets":                  emptyOK,
	"DeleteTenantSecret":                 emptyOK,
	"UpsertTenantSecret":                 emptyOK,
	"MigrateUserSecretScope":             emptyOK,
	"GetPoseidonUserSettings":            emptyOK,
	"GetPoseidonTenantSettings":          emptyOK,
	"UpdatePoseidonUserSettings":         emptyOK,
	"UpdatePoseidonTenantSettings":       emptyOK,
	"PinPoseidonSession":                 emptyOK,
	"UnpinPoseidonSession":               emptyOK,
	"CloudAgentsListAgents":              emptyOK,
	"CloudAgentsGetMessages":             emptyOK,
	"CloudAgentsBatchGetMessageCounts":   emptyOK,
	"CloudExpertsListExperts":            emptyOK,
	"AgentWorkspaceReportStatus":         emptyOK,
	"AgentWorkspaceReportLastSequenceId": emptyOK,
	"AgentWorkspaceGetLastSequenceId":    emptyOK,
	"AgentWorkspacePollUpdate":           emptyOK,
	"AgentWorkspaceReportChatHistory":    emptyOK,
	"AgentWorkspaceReportSetupLogs":      emptyOK,
	"ActionsGetUserState":                emptyOK,
	"ActionsSetUserState":                emptyOK,
	"PromptEnhancer":                     emptyOK,
	"ListExternalSourceTypes": func(_ *Responder, _ map[string]any) (any, error) {
		return map[string]any{"types": []any{}}, nil
	},
	// ---- webview-init-critical (were 501, blocking UI render) ------------------
	"ListWorkspaces": func(_ *Responder, _ map[string]any) (any, error) {
		return map[string]any{"workspaces": []any{}}, nil
	},
	"CreateWorkspace": emptyOK,
	"FindMissing": func(_ *Responder, _ map[string]any) (any, error) {
		return map[string]any{
			"unknown_memory_names":  []any{},
			"nonindexed_blob_names": []any{},
		}, nil
	},
	"OnboardingSessionEvent": emptyOK,
	"SearchExternalSources": func(_ *Responder, _ map[string]any) (any, error) {
		return map[string]any{"results": []any{}, "elapsed_ms": 0}, nil
	},
	"GetImplicitExternalSources": func(_ *Responder, _ map[string]any) (any, error) {
		return map[string]any{"sources": []any{}}, nil
	},
	"ReportError": emptyOK,
	"GetBillingSummary": func(_ *Responder, _ map[string]any) (any, error) {
		now := time.Now().UTC()
		return map[string]any{
			"current_period_start": now.Format(time.RFC3339),
			"current_period_end":   now.AddDate(0, 1, 0).Format(time.RFC3339),
			"credits_used":         0,
			"credits_limit":        1e6,
			"is_unlimited":         true,
		}, nil
	},
	"GetLatestIndexedCommitBlobset": func(_ *Responder, _ map[string]any) (any, error) {
		return map[string]any{"blobset": map[string]any{}, "commit_sha": ""}, nil
	},
	"RegisterIndexedCommitBlobset": emptyOK,
	// ---- connect services (stub — webview may ping) ----------------------------
	"Memorize":                emptyOK,
	"MarkNotificationAsRead":  emptyOK,
	"RevokeCurrentUserTokens": emptyOK,
	"RecordPreferenceSample":  emptyOK,
	"GetRemoteAgentChatHistory": func(_ *Responder, _ map[string]any) (any, error) {
		return map[string]any{"messages": []any{}}, nil
	},
	"Chat": (*Responder).chatUnary,
}

// ImplementedStreams are server-streaming RPCs we answer for real. ChatStream
// is the playable SSE simulator; the rest get the generic stub treatment.
var ImplementedStreams = map[string]bool{
	"ChatStream": true,
}

// Handle dispatches a unary RPC name to its handler. handled=false means the
// surface does not implement it (caller should return Unimplemented).
func (r *Responder) Handle(name string, req map[string]any) (resp any, handled bool, err error) {
	fn, ok := Implemented[name]
	if !ok {
		return nil, false, nil
	}
	resp, err = fn(r, req)
	return resp, true, err
}

// emptyOK answers with an empty object — safe for any response message because
// proto3 JSON omits zero fields.
func emptyOK(_ *Responder, _ map[string]any) (any, error) {
	return map[string]any{}, nil
}

func recordEvents(kind string) func(*Responder, map[string]any) (any, error) {
	return func(r *Responder, req map[string]any) (any, error) {
		events := req["events"]
		if arr, ok := events.([]any); ok {
			for _, e := range arr {
				if m, ok := e.(map[string]any); ok {
					r.Store.RecordEvent(kind, m)
				}
			}
		}
		log.Printf("surface: recorded %d %s event(s)", len(asSlice(events)), kind)
		return map[string]any{}, nil
	}
}

func asSlice(v any) []any {
	if a, ok := v.([]any); ok {
		return a
	}
	return nil
}

func (r *Responder) getModels(_ map[string]any) (any, error) {
	now := r.Store.Now().UTC().Format(time.RFC3339)
	models, df := buildModelList()
	flags := fullFeatureFlags(models)

	return map[string]any{
		"defaultModel": df,
		"models":       models,
		"languages":    []any{},
		"userTier":     "PROFESSIONAL_TIER",
		"user":         map[string]any{"id": "local-user", "email": "local@augment.local", "tenantId": "local-tenant", "tenantName": "Local", "createdAt": now},
		"featureFlags": flags,
	}, nil
}

// buildModelList reads CUSTOM_MODELS and returns the models array + default model
// key.  The env var can be:
//   - a JSON object {"model_key": {"displayName":"...", …}}, or
//   - a comma-separated list "model-a,model-b,model-c" (first is default).
//
// When unset, the built-in stub models are used.
func buildModelList() ([]any, string) {
	custom := os.Getenv("CUSTOM_MODELS")
	if custom == "" {
		return []any{
			map[string]any{"name": "augment-local-code-1", "internalName": "augment-local-code-1", "isDefault": true},
			map[string]any{"name": "augment-local-chat-1", "internalName": "augment-local-chat-1", "isDefault": false},
		}, "augment-local-code-1"
	}

	// Try JSON object first.
	var obj map[string]any
	if err := json.Unmarshal([]byte(custom), &obj); err == nil {
		models := make([]any, 0, len(obj))
		var first string
		for k, v := range obj {
			if first == "" {
				first = k
			}
			models = append(models, map[string]any{"name": k, "internalName": k, "isDefault": k == first})
			_ = v // displayName belongs in feature flags, not in proto name
		}
		return models, first
	}

	// Comma-separated list.
	keys := strings.Split(custom, ",")
	models := make([]any, 0, len(keys))
	var first string
	for _, k := range keys {
		k = strings.TrimSpace(k)
		if k == "" {
			continue
		}
		if first == "" {
			first = k
		}
		models = append(models, map[string]any{"name": k, "internalName": k, "isDefault": k == first})
	}
	if first == "" {
		first = "augment-local-code-1"
	}
	return models, first
}

// fullFeatureFlags returns every flag needed for the IDE to show all panels,
// tools, model pickers, and agent controls.  model_registry / model_info_registry /
// additional_chat_models are built dynamically from CUSTOM_MODELS (or the built-in
// stubs) so the UI model dropdown tracks the user's configuration.
func fullFeatureFlags(models []any) map[string]any {
	return map[string]any{
		// ---- core chat & editor -------------------------------------------------
		"enableChat":                      true,
		"enableIntellijChat":              true,
		"enableCodeEdits":                 true,
		"enableCompletions":               true,
		"enableChatInputInlineCompletion": true,
		"enableChatHistoryRecovery":       true,
		"enableChatMermaidDiagrams":       true,
		"enableDeepLinkChatWithPrompt":    true,
		"enableNewThreadsList":            true,
		"enableBulkDeleteThreads":         true,
		"enableExchangeStorage":           true,
		"enableSummaryTitles":             true,
		"intellijShowSummary":             true,

		// ---- workspace & sync ---------------------------------------------------
		"enableWorkspaceManagerUi":                   true,
		"enableWorkspaceManagerUiLaunch":             true,
		"enableWorkspaceConversationSync":            true,
		"enableWorkspaceConversationChatHistorySync": true,
		"enableCommitIndexing":                       true,
		"enableGitIndexing":                          false, // no git needed local
		"coldStartGitShaIndexEnabled":                false,

		// ---- agent mode & tools ------------------------------------------------
		"enableAgentAutoMode":                   true,
		"enableAgentTabs":                       true,
		"enableAgentGitTracker":                 true,
		"enableGroupedTools":                    true,
		"enableParallelTools":                   true,
		"enableApplyPatchTool":                  true,
		"enableSwarmMode":                       true,
		"grepSearchToolEnable":                  true,
		"ideEnableAskUserTool":                  true,
		"enableSubagents":                       true,
		"publicBetaEnableSubagents":             true,
		"beachheadEnableSubAgentTool":           true,
		"enableToolUseStateStorage":             true,
		"enableViewedContentTracking":           true,
		"agentEditToolSchemaType":               "json",
		"agentEditToolShowResultSnippet":        true,
		"agentEditToolEnableFuzzyMatching":      true,
		"agentEditToolInstructionsReminder":     false,
		"agentSaveFileToolInstructionsReminder": false,
		"agentReportStreamedChatEveryChunk":     1,
		"agentMaxIterations":                    100,
		"agentMaxTotalChangedFilesSizeBytes":    10 * 1024 * 1024,
		"agentIdleStatusUpdateIntervalMs":       5000,

		// ---- rules / skills / commands / hooks ---------------------------------
		"enableRules":                       true,
		"enableHierarchicalRules":           true,
		"enableSkills":                      true,
		"publicBetaEnableSkills":            true,
		"enableCustomCommands":              true,
		"publicBetaEnableCustomCommands":    true,
		"enableHooks":                       true,
		"enableGuidelines":                  true,
		"enableInstructions":                true,
		"enableSharedGuidelines":            true,
		"intellijEnableUserGuidelines":      true,
		"intellijEnableWorkspaceGuidelines": true,
		"intellijUserGuidelinesInSettings":  true,

		// ---- model selection ----------------------------------------------------
		// These fields are TYPE_STRING in the proto but carry JSON payloads:
		// - model_registry / additional_chat_models → Map<String,String> (Gson)
		// - model_info_registry → Map<String,ModelInfoRegistryEntry> (Gson)
		// Built dynamically from CUSTOM_MODELS env var (or the built-in stubs).
		"enableModelRegistry":                      true,
		"modelRegistry":                            modelRegistryJSON(models),
		"modelInfoRegistry":                        modelInfoRegistryJSON(models),
		"agentChatModel":                           asString(defaultModel(models)),
		"cloudAgentDefaultModelOverride":           "",
		"chatInputCompletionModel":                 asString(defaultModel(models)),
		"additionalChatModels":                     additionalChatModelsJSON(models),
		"enableDynamicModelSelector":               true,
		"enableModelSelectionByEditingChatHeader":  true,
		"enableModelSelectionWithoutExpandedInput": true,

		// ---- settings, analytics, debug -----------------------------------------
		"enableCreditsInSettings":            true,
		"enableCreditBannerInSettings":       true,
		"enableCreditsConsumedInTurnSummary": true,
		"enableSettingsHomePage":             true,
		"enablePluginMarketplace":            true,
		"enablePluginMarketplaceIde":         true,
		"enableDebugFeatures":                true,
		"enablePublicBetaPage":               true,
		"publicBetaOptInAll":                 true,
		"enableByok":                         false,
		"enableFigmaMcp":                     false,
		"enableTenantLevelToolPermissions":   false,

		// ---- context / retrieval / prompt ---------------------------------------
		"enableContextWindowUsage":        true,
		"enableContextUsageModal":         true,
		"enableContextCanvas":             false,
		"enableConversationRetrieval":     false,
		"enableHybridRetrieval":           true,
		"enableCodebaseRetrievalRaw":      true,
		"enableExternalSourcesInChat":     false,
		"enablePromptEnhancer":            false,
		"enableSmartPaste":                false,
		"enableMidStreamRetry":            true,
		"retryChatStreamTimeouts":         true,
		"enableUntruncatedContentStorage": true,

		// ---- notifications ------------------------------------------------------
		"enableNotificationsServiceIntellij":      true,
		"intellijEnableUpdateVersionNotification": true,

		// ---- onboarding / announcements -----------------------------------------
		"enableOnboardingV2": true,

		// ---- IntelliJ-specific --------------------------------------------------
		"intellijEnableFileIntakeService":            true,
		"intellijEnableHomespunGitignore":            true,
		"intellijEnableWebviewPerformanceMonitoring": false,
		"intellijPromptEnhancerEnabled":              false,
		"intellijEnableSentry":                       false,
		"intellijSidecarEnableSentry":                false,
		"intellijEnableSegmentAnalyticsReporting":    false,

		// ---- remote / cloud agents (disabled until backed by real handlers) -------
		"enableIdeHandoffToCloud":        false,
		"cliEnableCloudAgents":           false,
		"cliEnableHandoffToCloud":        false,
		"cliEnableCloudAgentAskUserTool": false,
		"cliEnablePersona":               true,
		"cliEnablePlanMode":              true,
		"cliEnableBashMode":              true,
		"cliEnableShowCredits":           true,

		// ---- misc quality-of-life ------------------------------------------------
		"enableViewTextDocument":            true,
		"enableHindsight":                   true,
		"enableCodeReviewSlashCommand":      true,
		"enableCommitSessionEvents":         true,
		"enableLucideIcons":                 true,
		"enableIntersectionObserverManager": true,
		"openFileManagerV2Enabled":          true,
		"useAcpCompletions":                 false,
		"webviewUseAcp":                     false,
	}
}

func (r *Responder) getCreditInfo(_ map[string]any) (any, error) {
	return map[string]any{
		"usage_units_remaining":                   1e6,
		"usage_units_total":                       1e6,
		"usage_units_total_current_billing_cycle": 1e6,
		"usage_units_total_additional":            0.0,
		"is_credit_balance_low":                   false,
		"included_usage_units_per_billing_cycle":  1e6,
		"current_billing_cycle_end_date_iso":      "2099-12-31",
		"refreshed_at":                            r.Store.Now().UTC().Format(time.RFC3339),
		"display_info":                            map[string]any{"total_used_percentage": 0, "num_days_left": 365},
		"credit_details":                          []any{},
	}, nil
}

func (r *Responder) listRemoteTools(_ map[string]any) (any, error) {
	// This RPC is only for cloud-hosted integrations represented by the
	// RemoteToolId enum. IDE-local tools (view, edit, terminal, retrieval, etc.)
	// are registered independently by the sidecar. Returning those tools here
	// duplicates them and feeds reserved enum values to the JVM client.
	return map[string]any{"tools": []any{}}, nil
}

func (r *Responder) chatInputCompletion(req map[string]any) (any, error) {
	empty := func() map[string]any {
		return map[string]any{
			"completion_items":     []any{},
			"unknown_memory_names": []any{},
			"checkpoint_not_found": false,
		}
	}

	prompt := boundedRunes(strings.TrimSpace(asString(req["prompt"])), 16*1024, true)
	if prompt == "" || r.GatewayURL == "" {
		return empty(), nil
	}
	suffix := boundedRunes(asString(req["suffix"]), 4*1024, false)
	model := strings.TrimSpace(asString(req["model"]))
	if model == "" {
		model = r.GatewayModel
	}
	if model == "" {
		return empty(), nil
	}

	userContent := "Current input:\n" + prompt
	if suffix != "" {
		userContent += "\n\nText after the cursor:\n" + suffix
	}
	body, err := json.Marshal(map[string]any{
		"model": model,
		"messages": []map[string]any{
			{"role": "system", "content": "Complete the user's IDE chat input. Return only a short natural continuation with no quotes, markdown, or explanation."},
			{"role": "user", "content": userContent},
		},
		"reasoning_effort": "low",
		"temperature":      0.2,
		"max_tokens":       96,
		"stream":           false,
	})
	if err != nil {
		return empty(), nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, openAIChatURL(r.GatewayURL), bytes.NewReader(body))
	if err != nil {
		return empty(), nil
	}
	request.Header.Set("Content-Type", "application/json")
	if key := os.Getenv("MODEL_GATEWAY_API_KEY"); key != "" {
		request.Header.Set("Authorization", "Bearer "+key)
	}
	client := r.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 3 * time.Second}
	}
	response, err := client.Do(request)
	if err != nil {
		log.Printf("surface: chat input completion unavailable: %v", err)
		return empty(), nil
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		log.Printf("surface: chat input completion gateway status=%d", response.StatusCode)
		return empty(), nil
	}

	var out struct {
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
			FinishReason string `json:"finish_reason"`
		} `json:"choices"`
	}
	decoder := json.NewDecoder(io.LimitReader(response.Body, 256*1024))
	if err := decoder.Decode(&out); err != nil || out.Error.Message != "" || len(out.Choices) == 0 {
		log.Printf("surface: chat input completion gateway returned no usable choice")
		return empty(), nil
	}
	text := boundedRunes(strings.TrimSpace(out.Choices[0].Message.Content), 512, false)
	if text == "" {
		return empty(), nil
	}
	finishReason := out.Choices[0].FinishReason
	if finishReason == "" {
		finishReason = "stop"
	}
	return map[string]any{
		"completion_items": []any{map[string]any{
			"text":          text,
			"finish_reason": finishReason,
		}},
		"unknown_memory_names": []any{},
		"checkpoint_not_found": false,
	}, nil
}

func openAIChatURL(baseURL string) string {
	url := strings.TrimSuffix(strings.TrimSpace(baseURL), "/v1")
	url = strings.TrimSuffix(url, "/")
	if !strings.Contains(url, "/chat/completions") {
		url += "/v1/chat/completions"
	}
	return url
}

func boundedRunes(value string, limit int, keepTail bool) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	if keepTail {
		return string(runes[len(runes)-limit:])
	}
	return string(runes[:limit])
}

// allRemoteTools returns the complete 26-tool definition set matching the real
// Augment cloud tool catalog. Each entry follows ListRemoteToolsResponse proto3
// JSON shape: camelCase field names matching the JVM deserializer.
func allRemoteTools() []any {
	return []any{
		// ── code retrieval (3) ──────────────────────────────────────────────
		tool("codebase-retrieval", "Augment's context engine. Searches the entire codebase using embeddings and metadata filters. Returns formatted retrieval results with file paths, symbols, and relevance scores. Use this for semantic understanding of the codebase — finding where a concept is implemented, discovering related code, or understanding architecture.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"information_request": {Type: "string", Description: "Natural language query describing what information you need from the codebase"},
				"dialog":              {Type: "object", Description: "Optional conversation context including request_message and history"},
			},
			Required: []string{"information_request"},
		}, 1),
		tool("git-commit-retrieval", "Retrieves git commit history, diffs, and metadata for the repository. Search by commit SHA, date range, author, or message content. Returns structured commit information including changed files, diff stats, and commit messages.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"query":  {Type: "string", Description: "Natural language or structured query for git commits (SHA, author, date range, keywords)"},
				"limit":  {Type: "integer", Description: "Maximum number of commits to return (default 20)"},
				"author": {Type: "string", Description: "Filter commits by author name or email"},
			},
			Required: []string{"query"},
		}, 2),
		tool("grep-search", "Fast regex and literal search across the entire codebase. Returns matching file paths, line numbers, and surrounding context. Supports full regex syntax, file type filtering, and exclusion patterns. Use this for finding exact symbol references, string literals, error messages, or patterns.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"query":               {Type: "string", Description: "Regex or literal search pattern"},
				"include":             {Type: "string", Description: "File glob pattern to include (e.g. '*.go', '**​/*.ts')"},
				"exclude":             {Type: "string", Description: "File glob pattern to exclude"},
				"max_results":         {Type: "integer", Description: "Maximum number of results to return (default 100)"},
				"case_sensitive":      {Type: "boolean", Description: "Whether the search is case-sensitive"},
				"include_binary":      {Type: "boolean", Description: "Whether to search binary files"},
				"max_file_size":       {Type: "integer", Description: "Skip files larger than this many bytes"},
				"search_non_code_too": {Type: "boolean", Description: "Also search non-code files (markdown, txt, config, etc.)"},
			},
			Required: []string{"query"},
		}, 3),

		// ── file browsing (3) ───────────────────────────────────────────────
		tool("view", "View the full contents of a file at a given path. Returns the complete file content with line numbers. Use this to read files you need to understand or edit.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"path": {Type: "string", Description: "Absolute or workspace-relative path to the file to view"},
			},
			Required: []string{"path"},
		}, 4),
		tool("view-range-untruncated", "View a specific range of lines from a file without truncation. Returns the exact line range requested with line numbers. Use this when you need to see specific sections of large files.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"path":       {Type: "string", Description: "Absolute or workspace-relative path to the file"},
				"start_line": {Type: "integer", Description: "First line number to view (1-indexed)"},
				"end_line":   {Type: "integer", Description: "Last line number to view (inclusive)"},
			},
			Required: []string{"path", "start_line", "end_line"},
		}, 5),
		tool("search-untruncated", "Search for text patterns in a specific file and return matching lines with surrounding context, untruncated. Combines grep-like search with file viewing.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"path":          {Type: "string", Description: "Absolute or workspace-relative path to search in"},
				"query":         {Type: "string", Description: "Regex or literal search pattern"},
				"context_lines": {Type: "integer", Description: "Number of context lines around each match (default 3)"},
			},
			Required: []string{"path", "query"},
		}, 6),

		// ── file editing (3) ────────────────────────────────────────────────
		tool("str-replace-editor", "Perform exact string replacements in an existing file. Each edit specifies old_string (must match exactly) and new_string. Multiple edits can be batched in a single call. The tool validates uniqueness of old_string before applying.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"path":  {Type: "string", Description: "Absolute or workspace-relative path to the file to edit"},
				"edits": {Type: "array", Description: "Array of {old_string, new_string, replace_all?} edit operations"},
			},
			Required: []string{"path", "edits"},
		}, 7),
		tool("save-file", "Create a new file or completely overwrite an existing file with the given content. Use this for creating new files or rewriting files entirely (not for partial edits — use str-replace-editor for that).", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"path":    {Type: "string", Description: "Absolute or workspace-relative path to write to"},
				"content": {Type: "string", Description: "Full file content to write"},
			},
			Required: []string{"path", "content"},
		}, 8),
		tool("remove-files", "Delete one or more files from the workspace. Accepts an array of file paths. Use with caution — deletions are permanent.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"paths": {Type: "array", Description: "Array of absolute or workspace-relative file paths to delete"},
			},
			Required: []string{"paths"},
		}, 9),

		// ── terminal / process (6) ──────────────────────────────────────────
		tool("launch-process", "Launch a new terminal process in the background. Returns a process ID for subsequent read/write/kill operations. The process runs in the project's working directory with the user's shell environment.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"command": {Type: "string", Description: "Shell command to execute"},
				"cwd":     {Type: "string", Description: "Working directory for the process (defaults to project root)"},
				"timeout": {Type: "integer", Description: "Timeout in milliseconds before auto-killing the process"},
			},
			Required: []string{"command"},
		}, 10),
		tool("read-process", "Read stdout and stderr output from a running or completed process. Returns buffered output since the last read. The process continues running after reading.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"process_id": {Type: "string", Description: "Process ID from launch-process"},
			},
			Required: []string{"process_id"},
		}, 11),
		tool("write-process", "Send input to a running process's stdin. Useful for interactive commands that require user input, password entry, or confirmation.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"process_id": {Type: "string", Description: "Process ID from launch-process"},
				"input":      {Type: "string", Description: "Text to send to the process stdin"},
			},
			Required: []string{"process_id", "input"},
		}, 12),
		tool("kill-process", "Terminate a running process by its process ID. Sends SIGTERM first, then SIGKILL if the process doesn't exit within a grace period.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"process_id": {Type: "string", Description: "Process ID from launch-process to terminate"},
			},
			Required: []string{"process_id"},
		}, 13),
		tool("list-processes", "List all running background processes with their IDs, commands, status, and uptime. Use this to check what's currently running before launching new processes.", toolSchema{
			Type:       "object",
			Properties: map[string]toolProp{},
		}, 14),
		tool("read-terminal", "Read the full terminal output from a process including ANSI escape sequences and interactive content. Returns complete terminal buffer, not just stdout. Use this for TUI applications or commands that produce rich terminal output.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"process_id": {Type: "string", Description: "Process ID from launch-process"},
			},
			Required: []string{"process_id"},
		}, 15),

		// ── web tools (3) ───────────────────────────────────────────────────
		tool("web-search", "Search the web and return formatted results with titles, URLs, and snippets. Use this to find current documentation, API references, news, or any information not in the local codebase.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"query":           {Type: "string", Description: "Search query string"},
				"allowed_domains": {Type: "array", Description: "Optional list of domains to restrict results to"},
				"blocked_domains": {Type: "array", Description: "Optional list of domains to exclude from results"},
			},
			Required: []string{"query"},
		}, 16),
		tool("web-fetch", "Fetch and parse a web page URL. Returns the page content converted to markdown or text. Use this to read documentation, API references, or any web resource. HTTP is upgraded to HTTPS. Supports redirects.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"url":    {Type: "string", Description: "The URL to fetch content from"},
				"prompt": {Type: "string", Description: "Optional prompt to extract specific information from the page"},
			},
			Required: []string{"url"},
		}, 17),
		tool("open-browser", "Open a URL in the system browser. Use this to show the user a web page, documentation, or UI that requires their interaction.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"url": {Type: "string", Description: "URL to open in the browser"},
			},
			Required: []string{"url"},
		}, 18),

		// ── task management (4) ─────────────────────────────────────────────
		tool("view_tasklist", "View the current task list. Returns all tasks with their IDs, statuses, descriptions, and dependencies. Use this to check progress before starting new work or updating tasks.", toolSchema{
			Type:       "object",
			Properties: map[string]toolProp{},
		}, 19),
		tool("add_tasks", "Add one or more new tasks to the task list. Each task has a subject, description, and optional dependencies. Returns the created task IDs.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"tasks": {Type: "array", Description: "Array of {subject, description, blocked_by?} task objects to add"},
			},
			Required: []string{"tasks"},
		}, 20),
		tool("update_tasks", "Update existing tasks: mark as completed, change status, update descriptions, or modify dependencies. Each update targets a specific task by ID.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"updates": {Type: "array", Description: "Array of {task_id, status?, description?, subject?, blocked_by?} update objects"},
			},
			Required: []string{"updates"},
		}, 21),
		tool("reorganize_tasklist", "Reorganize the task list: reorder tasks, merge duplicates, split large tasks, update dependency chains. Returns the reorganized task list.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"operations": {Type: "array", Description: "Array of reorder/merge/split/dependency operations to apply"},
			},
			Required: []string{"operations"},
		}, 22),

		// ── diagnostics (1) ─────────────────────────────────────────────────
		tool("diagnostics", "Read and display linter errors, compiler warnings, and IDE diagnostics from the current workspace. Returns diagnostics grouped by file with severity levels, line numbers, and messages. Use this to check for errors after making changes.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"paths": {Type: "array", Description: "Optional list of file/directory paths to check (defaults to all open files and recent changes)"},
			},
		}, 23),

		// ── memory (1) ──────────────────────────────────────────────────────
		tool("remember", "Store a key-value memory entry for future reference by the agent. Memories persist across turns within a conversation. Use this to remember user preferences, decisions, discovered facts, or context that should influence future responses.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"key":   {Type: "string", Description: "Memory key (unique identifier for this piece of information)"},
				"value": {Type: "string", Description: "Memory value to store"},
			},
			Required: []string{"key", "value"},
		}, 24),

		// ── diagram (1) ─────────────────────────────────────────────────────
		tool("render-mermaid", "Render a Mermaid.js diagram and display it in the chat. Supports flowchart, sequence, class, state, ER, gantt, and pie diagrams. Use this to visualize architecture, workflows, data models, or timelines.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"mermaid": {Type: "string", Description: "Mermaid.js diagram source code"},
			},
			Required: []string{"mermaid"},
		}, 25),

		// ── agent / subagent (1) ────────────────────────────────────────────
		tool("task", "Launch a subagent to handle a complex, independent task. The subagent has its own tool access and runs in parallel. Use this to delegate research, large-scale searches, or parallelizable work.", toolSchema{
			Type: "object",
			Properties: map[string]toolProp{
				"subagent_type": {Type: "string", Description: "Type of subagent to use (e.g. 'general-purpose', 'Explore')"},
				"description":   {Type: "string", Description: "Short description of the task (3-5 words)"},
				"prompt":        {Type: "string", Description: "Detailed instructions for the subagent"},
			},
			Required: []string{"description", "prompt"},
		}, 26),
	}
}

// toolSchema is the JSON Schema structure embedded as inputSchemaJson in
// ToolDefinition. It matches the snake_case field names the JVM deserializer
// expects inside input_schema_json.
type toolSchema struct {
	Type       string              `json:"type"`
	Properties map[string]toolProp `json:"properties"`
	Required   []string            `json:"required,omitempty"`
}

type toolProp struct {
	Type        string `json:"type"`
	Description string `json:"description,omitempty"`
}

// tool builds one ListRemoteToolsResponse entry.
func tool(name, description string, schema toolSchema, id int) map[string]any {
	schemaBytes, err := json.Marshal(schema)
	if err != nil {
		// This is a static definition; a marshal error means a code bug.
		panic("tool " + name + ": " + err.Error())
	}
	return map[string]any{
		"toolDefinition": map[string]any{
			"name":              name,
			"description":       description,
			"input_schema_json": string(schemaBytes),
			"tool_safety":       1,
		},
		"remoteToolId":       id,
		"availabilityStatus": 1, // AVAILABLE
		"toolSafety":         1,
		"oauthUrl":           "",
	}
}

func (r *Responder) checkToolSafety(req map[string]any) (any, error) {
	log.Printf("surface: check-tool-safety input=%v", req["tool_input_json"])
	return map[string]any{"is_safe": true}, nil
}

func (r *Responder) runRemoteTool(req map[string]any) (any, error) {
	name := asString(req["tool_name"])
	if name == "" {
		name = asString(req["name"])
	}
	toolID := req["tool_id"]
	log.Printf("surface: run-remote-tool name=%s tool_id=%v", name, toolID)

	// The input may arrive as a JSON object or a double-encoded JSON string.
	var input json.RawMessage
	v := req["tool_input_json"]
	if v == nil {
		v = req["input"]
	}
	if v != nil {
		switch x := v.(type) {
		case string:
			input = json.RawMessage(x)
		case map[string]any:
			b, _ := json.Marshal(x)
			input = json.RawMessage(b)
		default:
			b, _ := json.Marshal(v)
			input = json.RawMessage(b)
		}
	}

	if r.ToolExecutor != nil {
		tr := &tools.ToolCallRequest{
			Name:      name,
			RequestID: fmt.Sprint(toolID),
			Input:     input,
		}
		res := r.ToolExecutor.Execute(tr)
		status := "EXECUTION_SUCCESS"
		message := ""
		if res.IsError {
			status = "EXECUTION_ERROR"
			message = res.Text
		}
		return map[string]any{
			"tool_output":            res.Text,
			"tool_result_message":    message,
			"status":                 status,
			"compressed_full_output": "",
			"full_output_size":       len([]byte(res.Text)),
			"content_nodes":          []any{},
		}, nil
	}

	return map[string]any{
		"tool_output":            fmt.Sprintf("tool %s is not available", name),
		"tool_result_message":    "remote tool executor is not configured",
		"status":                 "NOT_AVAILABLE",
		"compressed_full_output": "",
		"full_output_size":       0,
		"content_nodes":          []any{},
	}, nil
}

func (r *Responder) codebaseRetrieval(req map[string]any) (any, error) {
	q, _ := req["information_request"].(string)
	if q == "" {
		for _, item := range asSlice(req["dialog"]) {
			if exchange, ok := item.(map[string]any); ok {
				if message := asString(exchange["request_message"]); message != "" {
					q = message
				}
			}
		}
	}
	resp := map[string]any{
		"formatted_retrieval":               "",
		"codebase_retrieval_elapsed_ms":     0,
		"conversation_retrieval_elapsed_ms": 0,
		"codebase_chunks_retrieved":         0,
		"conversation_chunks_combined":      0,
		"codebase_truncated":                false,
		"conversation_truncated":            false,
		"final_truncated":                   false,
	}
	if q == "" {
		resp["formatted_retrieval"] = "Codebase retrieval skipped: information_request is required."
		return resp, nil
	}
	if r.ToolExecutor == nil {
		resp["formatted_retrieval"] = "Codebase retrieval unavailable: local tool executor is not configured."
		return resp, nil
	}

	input, _ := json.Marshal(map[string]any{"information_request": q})
	started := time.Now()
	result := r.ToolExecutor.Execute(&tools.ToolCallRequest{
		Name:           "codebase-retrieval",
		Input:          input,
		ConversationID: asString(req["conversation_id"]),
	})
	resp["codebase_retrieval_elapsed_ms"] = int(time.Since(started).Milliseconds())
	if result == nil {
		resp["formatted_retrieval"] = "Codebase retrieval failed: the local tool executor returned no result."
		return resp, nil
	}
	formatted := strings.TrimSpace(result.Text)
	if formatted == "" {
		formatted = "No matching codebase context was found for this request."
	}
	if result.IsError {
		formatted = "Codebase retrieval failed: " + formatted
	}
	resp["formatted_retrieval"] = formatted
	resp["codebase_result_len"] = len(formatted)
	resp["combined_result_len"] = len(formatted)
	return resp, nil
}

func (r *Responder) codebaseRetrievalRaw(_ map[string]any) (any, error) {
	return map[string]any{"chunks": []any{}, "formatted_retrieval": ""}, nil
}

func (r *Responder) listRemoteAgents(_ map[string]any) (any, error) {
	return map[string]any{"remote_agents": []any{}, "max_remote_agents": 0, "max_active_remote_agents": 0}, nil
}

func (r *Responder) createConversation(req map[string]any) (any, error) {
	id, _ := req["conversation_id"].(string)
	if id == "" {
		id = "conv_" + time.Now().UTC().Format("20060102T150405.000")
	}
	title, _ := req["title"].(string)
	ws, _ := req["workspace_id"].(string)
	pinned, _ := req["is_pinned"].(bool)
	r.Store.CreateConversation(id, ws, title, pinned)
	return map[string]any{"conversation_id": id}, nil
}

func (r *Responder) getConversation(req map[string]any) (any, error) {
	id, _ := req["conversation_id"].(string)
	c, ok := r.Store.GetConversation(id)
	if !ok {
		return map[string]any{}, nil
	}
	return map[string]any{"conversation": convJSON(c)}, nil
}

func (r *Responder) updateConversation(req map[string]any) (any, error) {
	id, _ := req["conversation_id"].(string)
	title, _ := req["title"].(string)
	var pinned *bool
	if v, ok := req["is_pinned"].(bool); ok {
		pinned = &v
	}
	c := r.Store.UpdateConversation(id, title, pinned)
	return map[string]any{"conversation": convJSON(c)}, nil
}

func (r *Responder) listConversations(req map[string]any) (any, error) {
	ws, _ := req["workspace_id"].(string)
	convs := r.Store.ListConversations(ws)
	out := make([]any, 0, len(convs))
	for _, c := range convs {
		out = append(out, convJSON(c))
	}
	return map[string]any{"conversations": out}, nil
}

func convJSON(c *state.Conversation) map[string]any {
	return map[string]any{
		"conversation_id": c.ID,
		"workspace_id":    c.WorkspaceID,
		"title":           c.Title,
		"is_pinned":       c.IsPinned,
		"created_at":      c.CreatedAt.UTC().Format(time.RFC3339),
		"updated_at":      c.UpdatedAt.UTC().Format(time.RFC3339),
	}
}

func (r *Responder) listChatHistory(req map[string]any) (any, error) {
	id, _ := req["conversation_id"].(string)
	limit := 0
	if l, ok := req["limit"].(float64); ok {
		limit = int(l)
	}
	exs := r.Store.ListExchanges(id, limit)
	out := make([]any, 0, len(exs))
	for _, e := range exs {
		out = append(out, map[string]any{
			"request_id":      e.RequestID,
			"request_message": e.RequestMsg,
			"response_text":   e.ResponseText,
			"turn_id":         e.TurnID,
			"created_at":      e.CreatedAt.UTC().Format(time.RFC3339),
		})
	}
	return map[string]any{"chat_history": out}, nil
}

func (r *Responder) countChatHistory(req map[string]any) (any, error) {
	conversationIDs := asSlice(req["conversation_ids"])
	results := make([]any, 0, len(conversationIDs))
	for _, rawID := range conversationIDs {
		id, _ := rawID.(string)
		results = append(results, map[string]any{
			"conversation_id": id,
			"count":           len(r.Store.ListExchanges(id, 0)),
		})
	}
	return map[string]any{"results": results}, nil
}

func (r *Responder) insertChatHistory(req map[string]any) (any, error) {
	conversationID, _ := req["conversation_id"].(string)
	exchanges := asSlice(req["exchanges"])
	entries := make([]any, 0, len(exchanges))
	for i, rawEntry := range exchanges {
		entry, entryOK := rawEntry.(map[string]any)
		exchange, exchangeOK := entry["exchange"].(map[string]any)
		if !entryOK || !exchangeOK || conversationID == "" {
			entries = append(entries, insertChatHistoryEntry(i, 3, "invalid chat history entry"))
			continue
		}

		turnID := ""
		if metadata, ok := entry["metadata"].(map[string]any); ok {
			turnID, _ = metadata["turn_id"].(string)
		}
		r.Store.AppendExchange(conversationID, &state.Exchange{
			RequestID:    asString(exchange["request_id"]),
			RequestMsg:   asString(exchange["request_message"]),
			ResponseText: asString(exchange["response_text"]),
			TurnID:       turnID,
		})
		entries = append(entries, insertChatHistoryEntry(i, 0, ""))
	}
	return map[string]any{"entries": entries}, nil
}

func insertChatHistoryEntry(index, code int, message string) map[string]any {
	status := map[string]any{"code": code}
	if message != "" {
		status["message"] = message
	}
	return map[string]any{
		"index":  index,
		"status": status,
	}
}

func (r *Responder) saveChat(req map[string]any) (any, error) {
	convID, _ := req["conversation_id"].(string)
	uuid := "uuid-" + time.Now().UTC().Format("20060102T150405.000")
	return map[string]any{"uuid": uuid, "url": r.TenantURL + "/saved/" + uuid, "conversation_id": convID}, nil
}

func (r *Responder) getSubscriptionInfo(_ map[string]any) (any, error) {
	return map[string]any{
		"active_subscription":   map[string]any{"usage_balance_depleted": false},
		"inactive_subscription": map[string]any{},
		"feature_gating_info":   map[string]any{},
	}, nil
}

// ---- model-registry JSON helpers ------------------------------------------
// asString extracts a string value from an any interface, returning ""
// when the underlying type is not string.
func asString(v any) string {
	s, _ := v.(string)
	return s
}

// These produce the JSON-string values for model_registry,
// model_info_registry, additional_chat_models, and agent_chat_model.

func modelRegistryJSON(models []any) string {
	// model_registry is Map<String,String>: key = model key, value = display name.
	out := map[string]string{}
	for _, m := range models {
		mm, _ := m.(map[string]any)
		key, _ := mm["internalName"].(string)
		name, _ := mm["name"].(string)
		if key == "" {
			key = name
		}
		if key != "" {
			out[key] = name
		}
	}
	b, _ := json.Marshal(out)
	return string(b)
}

func modelInfoRegistryJSON(models []any) string {
	// model_info_registry is Map<String,ModelInfoRegistryEntry>.
	out := map[string]any{}
	for i, m := range models {
		mm, _ := m.(map[string]any)
		key, _ := mm["internalName"].(string)
		if key == "" {
			key, _ = mm["name"].(string)
		}
		entry := map[string]any{
			"displayName":   mm["name"],
			"shortName":     mm["name"],
			"description":   "",
			"effortLevels":  []string{"low", "medium", "high"},
			"isDefault":     i == 0,
			"priority":      i + 1,
			"modelGroup":    "Custom",
			"isLegacyModel": false,
			"costTier":      0,
			"isRouter":      false,
			"provider":      "custom",
		}
		out[key] = entry
	}
	b, _ := json.Marshal(out)
	return string(b)
}

func additionalChatModelsJSON(models []any) string {
	out := map[string]string{}
	for _, m := range models {
		mm, _ := m.(map[string]any)
		key, _ := mm["internalName"].(string)
		name, _ := mm["name"].(string)
		if key == "" {
			key = name
		}
		if key != "" {
			out[key] = name
		}
	}
	b, _ := json.Marshal(out)
	return string(b)
}

func defaultModel(models []any) any {
	if len(models) == 0 {
		return "augment-local-chat-1"
	}
	mm, _ := models[0].(map[string]any)
	if k, ok := mm["internalName"].(string); ok {
		return k
	}
	return mm["name"]
}
