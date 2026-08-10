// Package discovery serves the client_discovery table: one entry for every
// ClientServiceType the plugin knows about, all pointed at the local tenant
// surface. The wire shape matches augment.client_discovery.ClientDiscoveryResponse
// in proto3-JSON form (enum names as strings, grpc transport config).
package discovery

import (
	"encoding/json"
	"net/http"
)

// ServiceType mirrors augment.client_discovery.ClientServiceType in declaration
// order — the numeric values are the proto enum numbers.
var ServiceType = []struct{ Name string; Number int32 }{
	{"ECHO", 0}, {"KV_STORE", 1}, {"CLIENT_FEATURE_FLAGS", 2}, {"INTAKE", 3},
	{"SETTINGS_WEBVIEW_COMMUNICATION", 4}, {"HIERARCHICAL_RULES", 5}, {"USER_WORKSPACE", 6},
	{"CONVERSATION", 7}, {"CHAT_HISTORY", 8}, {"AUGMENT", 9}, {"CHAT_INPUT_COMPLETION", 10},
	{"CUSTOM_COMMANDS", 11}, {"REPOSITORY_ALLOWLIST", 12}, {"INDEXING", 13}, {"SKILLS", 14},
	{"RULES_MANAGEMENT", 15}, {"HTTP_HEALTH_CHECK", 16}, {"ASK_USER", 17}, {"PLUGIN_MARKETPLACE", 18},
	{"HOOKS", 19}, {"AGENTS", 20}, {"ACP", 21},
}

// Table builds the discovery response for a tenant address.
func Table(tenantURL string) map[string]any {
	services := make([]any, 0, len(ServiceType))
	for _, st := range ServiceType {
		services = append(services, st.Name)
	}
	// A single ClientDiscovery entry advertising all supported services, with
	// the grpc oneof populated. The whole table is served on one port.
	return map[string]any{
		"transports": []any{
			map[string]any{
				"supported_services": services,
				"grpc": map[string]any{
					"base_url":     tenantURL,
					"rpc_path":     "/augment.public_api.Augment",
					"full_rpc_url": tenantURL,
					"port":         8787,
				},
			},
		},
	}
}

// Handler answers POST /api-client/client-discovery (and bare POST /client-discovery).
func Handler(tenantURL string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(Table(tenantURL))
	}
}
