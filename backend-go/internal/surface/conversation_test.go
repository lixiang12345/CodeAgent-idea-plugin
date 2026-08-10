package surface

import (
	"testing"

	"augment-local/internal/state"
)

func TestConversationResponsesMatchDescriptorWrappers(t *testing.T) {
	store := state.New()
	responder := &Responder{Store: store}
	store.CreateConversation("conversation-1", "workspace-1", "Original", false)

	response, handled, err := responder.Handle("GetConversation", map[string]any{
		"workspace_id":    "workspace-1",
		"conversation_id": "conversation-1",
	})
	if err != nil || !handled {
		t.Fatalf("get conversation handled=%v err=%v", handled, err)
	}
	conversation := response.(map[string]any)["conversation"].(map[string]any)
	if conversation["conversation_id"] != "conversation-1" || conversation["title"] != "Original" {
		t.Fatalf("get conversation response = %#v", response)
	}

	response, handled, err = responder.Handle("UpdateConversation", map[string]any{
		"workspace_id":    "workspace-1",
		"conversation_id": "conversation-1",
		"title":           "Updated",
		"is_pinned":       true,
	})
	if err != nil || !handled {
		t.Fatalf("update conversation handled=%v err=%v", handled, err)
	}
	conversation = response.(map[string]any)["conversation"].(map[string]any)
	if conversation["title"] != "Updated" || conversation["is_pinned"] != true {
		t.Fatalf("update conversation response = %#v", response)
	}
}

func TestCountChatHistoryReturnsOneResultPerConversation(t *testing.T) {
	store := state.New()
	responder := &Responder{Store: store}
	store.AppendExchange("conversation-1", &state.Exchange{RequestID: "request-1"})
	store.AppendExchange("conversation-1", &state.Exchange{RequestID: "request-2"})
	store.AppendExchange("conversation-2", &state.Exchange{RequestID: "request-3"})

	response, handled, err := responder.Handle("CountChatHistory", map[string]any{
		"conversation_ids": []any{"conversation-1", "conversation-2", "conversation-empty"},
	})
	if err != nil || !handled {
		t.Fatalf("count chat history handled=%v err=%v", handled, err)
	}
	results := response.(map[string]any)["results"].([]any)
	if len(results) != 3 {
		t.Fatalf("results = %#v", results)
	}
	want := []int{2, 1, 0}
	for i, result := range results {
		entry := result.(map[string]any)
		if entry["count"] != want[i] {
			t.Fatalf("result %d = %#v, want count %d", i, entry, want[i])
		}
	}
}

func TestInsertChatHistoryPersistsValidEntriesAndReportsInvalidEntries(t *testing.T) {
	store := state.New()
	responder := &Responder{Store: store}

	response, handled, err := responder.Handle("InsertChatHistory", map[string]any{
		"conversation_id": "conversation-1",
		"exchanges": []any{
			map[string]any{
				"exchange": map[string]any{
					"request_id":      "request-1",
					"request_message": "question",
					"response_text":   "answer",
				},
				"metadata": map[string]any{"turn_id": "turn-1"},
			},
			map[string]any{"metadata": map[string]any{}},
		},
	})
	if err != nil || !handled {
		t.Fatalf("insert chat history handled=%v err=%v", handled, err)
	}
	entries := response.(map[string]any)["entries"].([]any)
	if len(entries) != 2 {
		t.Fatalf("entries = %#v", entries)
	}
	if code := entries[0].(map[string]any)["status"].(map[string]any)["code"]; code != 0 {
		t.Fatalf("valid entry status = %#v", entries[0])
	}
	if code := entries[1].(map[string]any)["status"].(map[string]any)["code"]; code != 3 {
		t.Fatalf("invalid entry status = %#v", entries[1])
	}
	exchanges := store.ListExchanges("conversation-1", 0)
	if len(exchanges) != 1 || exchanges[0].RequestID != "request-1" || exchanges[0].TurnID != "turn-1" {
		t.Fatalf("persisted exchanges = %#v", exchanges)
	}
}

func TestFindMissingUsesPublicAPIFieldNames(t *testing.T) {
	responder := &Responder{Store: state.New()}
	response, handled, err := responder.Handle("FindMissing", map[string]any{
		"mem_object_names": []any{"memory-1"},
	})
	if err != nil || !handled {
		t.Fatalf("find missing handled=%v err=%v", handled, err)
	}
	fields := response.(map[string]any)
	if _, ok := fields["unknown_memory_names"].([]any); !ok {
		t.Fatalf("unknown_memory_names = %#v", fields["unknown_memory_names"])
	}
	if _, ok := fields["nonindexed_blob_names"].([]any); !ok {
		t.Fatalf("nonindexed_blob_names = %#v", fields["nonindexed_blob_names"])
	}
}
