package surface

import (
	"reflect"
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
