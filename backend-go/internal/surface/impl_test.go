package surface

import (
	"bytes"
	"log"
	"reflect"
	"strings"
	"testing"
)

func TestBuildModelListPreservesConfiguredJSONOrder(t *testing.T) {
	t.Setenv("CUSTOM_MODELS", `{"gpt-5.6-sol":{"displayName":"GPT"},"claude-sonnet":{"displayName":"Claude"},"gemini-pro":{"displayName":"Gemini"}}`)

	models, defaultModel := buildModelList()
	if defaultModel != "gpt-5.6-sol" {
		t.Fatalf("default model = %q, want first configured model", defaultModel)
	}

	got := make([]string, 0, len(models))
	for _, model := range models {
		got = append(got, asString(model.(map[string]any)["name"]))
	}
	want := []string{"gpt-5.6-sol", "claude-sonnet", "gemini-pro"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("model order = %v, want %v", got, want)
	}
}

func TestCheckToolSafetyDoesNotLogToolInput(t *testing.T) {
	var logs bytes.Buffer
	previousWriter := log.Writer()
	previousFlags := log.Flags()
	log.SetOutput(&logs)
	log.SetFlags(0)
	t.Cleanup(func() {
		log.SetOutput(previousWriter)
		log.SetFlags(previousFlags)
	})

	const secret = "tool-input-secret-must-not-reach-logs"
	responder := &Responder{}
	_, err := responder.checkToolSafety(map[string]any{
		"tool_input_json": `{"api_key":"` + secret + `"}`,
	})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(logs.String(), secret) {
		t.Fatalf("tool input leaked into logs: %s", logs.String())
	}
}

func TestFeatureFlagsAdvertiseOnlyBackedCoreWorkflows(t *testing.T) {
	models, _ := buildModelList()
	flags := fullFeatureFlags(models)

	for _, name := range []string{
		"enableChat",
		"enableIntellijChat",
		"enableCodeEdits",
		"enableCompletions",
		"enableChatInputInlineCompletion",
		"enableRules",
		"enableSkills",
		"enableHooks",
	} {
		if enabled, ok := flags[name].(bool); !ok || !enabled {
			t.Errorf("backed core feature %s = %#v, want true", name, flags[name])
		}
	}

	for _, name := range []string{
		"enableByok",
		"enableFigmaMcp",
		"enableTenantLevelToolPermissions",
		"enableContextCanvas",
		"enableConversationRetrieval",
		"enableCodebaseRetrievalRaw",
		"enableExternalSourcesInChat",
		"enablePromptEnhancer",
		"enableSmartPaste",
		"enableBulkDeleteThreads",
		"enableWorkspaceManagerUi",
		"enableWorkspaceManagerUiLaunch",
		"enableWorkspaceConversationSync",
		"enableWorkspaceConversationChatHistorySync",
		"enableCommitIndexing",
		"intellijPromptEnhancerEnabled",
		"intellijEnableFileIntakeService",
		"enableIdeHandoffToCloud",
		"cliEnableCloudAgents",
		"cliEnableHandoffToCloud",
		"cliEnableCloudAgentAskUserTool",
	} {
		if enabled, ok := flags[name].(bool); !ok || enabled {
			t.Errorf("unsupported feature %s = %#v, want false", name, flags[name])
		}
	}
}

func TestUnsupportedServerStreamsAreNotRegisteredAsUnaryHandlers(t *testing.T) {
	unsupportedStreams := []string{
		"PromptEnhancer",
		"GenerateCommitMessageStream",
		"SmartPasteStream",
		"ListRemoteAgentsStream",
		"GetRemoteAgentHistoryStream",
		"AgentWorkspaceStream",
		"GetLatestIndexedCommitBlobset",
		"CloudAgentsGetMessagesStream",
	}
	for _, name := range unsupportedStreams {
		if _, ok := Implemented[name]; ok {
			t.Errorf("unsupported server stream %q is registered as a unary handler", name)
		}
		if ImplementedStreams[name] {
			t.Errorf("unsupported server stream %q is advertised as implemented", name)
		}
	}
	if !ImplementedStreams["ChatStream"] {
		t.Error("ChatStream is not registered as the supported server stream")
	}
}

func TestImplementedHandlersAreReachableFromGeneratedRoutes(t *testing.T) {
	routeNames := make(map[string]struct{}, len(Routes))
	for _, route := range Routes {
		routeNames[route.Name] = struct{}{}
	}
	for name := range Implemented {
		if _, ok := routeNames[name]; !ok {
			t.Errorf("implemented handler %q has no generated RPC route", name)
		}
	}
}
