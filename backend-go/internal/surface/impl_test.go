package surface

import "testing"

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
