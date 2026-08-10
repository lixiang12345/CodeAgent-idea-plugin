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
		"enableExternalSourcesInChat",
		"enablePromptEnhancer",
		"enableSmartPaste",
		"intellijPromptEnhancerEnabled",
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
