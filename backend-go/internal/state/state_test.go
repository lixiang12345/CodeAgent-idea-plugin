package state

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"testing"
	"time"
)

func TestCloseAndRestartRestoreSnapshot(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	store := NewWithSnapshot(path)

	created := store.CreateConversation("conv-1", "workspace-1", "First title", true)
	store.AppendExchange("conv-1", &Exchange{
		RequestID:    "request-1",
		RequestMsg:   "question",
		ResponseText: "answer",
		TurnID:       "turn-1",
		IsToolTurn:   true,
		ToolCalls: []ToolCall{{
			ID: "tool-1", Name: "view", Arguments: `{"path":"README.md"}`,
		}},
	})
	store.AddCreditUsage(1.25)

	if err := store.Close(); err != nil {
		t.Fatalf("close store: %v", err)
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatalf("temporary snapshot remains after close: %v", err)
	}

	restarted := NewWithSnapshot(path)
	t.Cleanup(func() { _ = restarted.Close() })

	conversation, ok := restarted.GetConversation(created.ID)
	if !ok {
		t.Fatal("conversation was not restored")
	}
	if conversation.Title != "First title" || !conversation.IsPinned || conversation.WorkspaceID != "workspace-1" {
		t.Fatalf("restored conversation = %#v", conversation)
	}
	exchanges := restarted.ListExchanges("conv-1", 0)
	if len(exchanges) != 1 {
		t.Fatalf("restored exchanges = %d, want 1", len(exchanges))
	}
	if got := exchanges[0]; got.ResponseText != "answer" || len(got.ToolCalls) != 1 || got.ToolCalls[0].Name != "view" {
		t.Fatalf("restored exchange = %#v", got)
	}
	if got := restarted.CreditsUsed(); got != 1.25 {
		t.Fatalf("restored credits = %v, want 1.25", got)
	}
}

func TestStoreAndSnapshotOwnDeepCopies(t *testing.T) {
	store := New()
	created := store.CreateConversation("conv", "workspace", "original", false)
	created.Title = "caller mutation"

	exchange := &Exchange{
		RequestID: "request",
		ToolCalls: []ToolCall{{ID: "tool", Name: "view", Arguments: `{}`}},
	}
	store.AppendExchange("conv", exchange)
	exchange.RequestID = "caller mutation"
	exchange.ToolCalls[0].Name = "caller mutation"

	conversation, ok := store.GetConversation("conv")
	if !ok || conversation.Title != "original" {
		t.Fatalf("store retained caller-owned conversation: %#v", conversation)
	}
	conversation.Title = "returned mutation"
	conversationAgain, _ := store.GetConversation("conv")
	if conversationAgain.Title != "original" {
		t.Fatalf("GetConversation exposed internal state: %#v", conversationAgain)
	}

	exchanges := store.ListExchanges("conv", 0)
	if exchanges[0].RequestID != "request" || exchanges[0].ToolCalls[0].Name != "view" {
		t.Fatalf("store retained caller-owned exchange: %#v", exchanges[0])
	}
	exchanges[0].ToolCalls[0].Name = "returned mutation"
	if got := store.ListExchanges("conv", 0)[0].ToolCalls[0].Name; got != "view" {
		t.Fatalf("ListExchanges exposed internal tool calls: %q", got)
	}

	store.mu.Lock()
	snap := store.snapshotLocked()
	store.mu.Unlock()
	pinned := true
	store.UpdateConversation("conv", "updated", &pinned)
	store.AppendExchange("conv", &Exchange{RequestID: "request-2"})

	if got := snap.Conversations["conv"].Title; got != "original" {
		t.Fatalf("snapshot conversation changed with store: %q", got)
	}
	if got := len(snap.Exchanges["conv"]); got != 1 {
		t.Fatalf("snapshot exchanges changed with store: %d", got)
	}
}

func TestFailedFlushKeepsDirtyAndCanRetry(t *testing.T) {
	parent := t.TempDir()
	path := filepath.Join(parent, "state-target")
	if err := os.Mkdir(path, 0o755); err != nil {
		t.Fatal(err)
	}
	store := NewWithSnapshot(path)
	store.CreateConversation("conv", "", "retry me", false)

	if err := store.Close(); err == nil {
		t.Fatal("close unexpectedly succeeded when snapshot target was a directory")
	}
	store.mu.Lock()
	dirty := store.dirty
	store.mu.Unlock()
	if !dirty {
		t.Fatal("failed snapshot cleared dirty state")
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatalf("failed snapshot left temporary file behind: %v", err)
	}

	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatalf("retry close: %v", err)
	}

	restarted := NewWithSnapshot(path)
	t.Cleanup(func() { _ = restarted.Close() })
	if conversation, ok := restarted.GetConversation("conv"); !ok || conversation.Title != "retry me" {
		t.Fatalf("retry did not persist conversation: %#v, %v", conversation, ok)
	}
}

func TestListConversationsNewestFirstAndStable(t *testing.T) {
	store := New()
	current := time.Date(2026, 8, 10, 10, 0, 0, 0, time.UTC)
	store.now = func() time.Time { return current }

	store.CreateConversation("b", "workspace", "b", false)
	store.CreateConversation("a", "workspace", "a", false)
	current = current.Add(time.Minute)
	store.CreateConversation("newest", "workspace", "newest", false)

	want := []string{"newest", "a", "b"}
	for i := 0; i < 50; i++ {
		if got := conversationIDs(store.ListConversations("workspace")); !reflect.DeepEqual(got, want) {
			t.Fatalf("iteration %d order = %v, want %v", i, got, want)
		}
	}

	current = current.Add(time.Minute)
	store.UpdateConversation("b", "updated", nil)
	want = []string{"b", "newest", "a"}
	if got := conversationIDs(store.ListConversations("workspace")); !reflect.DeepEqual(got, want) {
		t.Fatalf("updated order = %v, want %v", got, want)
	}
}

func TestCorruptSnapshotStartsEmptyAndRecovers(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	if err := os.WriteFile(path, []byte(`{"conversations":`), 0o600); err != nil {
		t.Fatal(err)
	}

	store := NewWithSnapshot(path)
	if got := store.ListConversations(""); len(got) != 0 {
		t.Fatalf("corrupt snapshot loaded conversations: %#v", got)
	}
	store.CreateConversation("recovered", "", "Recovered", false)
	if err := store.Close(); err != nil {
		t.Fatalf("close after corrupt snapshot: %v", err)
	}

	var persisted snapshot
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, &persisted); err != nil {
		t.Fatalf("replacement snapshot is invalid: %v", err)
	}
	if persisted.Conversations["recovered"] == nil {
		t.Fatal("replacement snapshot omitted recovered conversation")
	}
}

func TestAutoSavePersistsAndCloseStopsLoop(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	store := newWithSnapshot(path, 2*time.Millisecond)
	store.CreateConversation("autosaved", "", "Autosaved", false)

	deadline := time.Now().Add(time.Second)
	for {
		var persisted snapshot
		raw, err := os.ReadFile(path)
		if err == nil && json.Unmarshal(raw, &persisted) == nil && persisted.Conversations["autosaved"] != nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("autosave did not persist state before deadline")
		}
		time.Sleep(time.Millisecond)
	}

	done := store.doneCh
	if err := store.Close(); err != nil {
		t.Fatalf("close autosaved store: %v", err)
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("autosave loop did not stop on Close")
	}
	if err := store.Close(); err != nil {
		t.Fatalf("second Close: %v", err)
	}
}

func TestConcurrentMutationAndSnapshot(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	store := newWithSnapshot(path, time.Hour)

	const workers = 8
	const exchangesPerWorker = 100
	for worker := 0; worker < workers; worker++ {
		id := fmt.Sprintf("conv-%d", worker)
		store.CreateConversation(id, "workspace", id, false)
	}

	var wg sync.WaitGroup
	for worker := 0; worker < workers; worker++ {
		worker := worker
		wg.Add(1)
		go func() {
			defer wg.Done()
			id := fmt.Sprintf("conv-%d", worker)
			for i := 0; i < exchangesPerWorker; i++ {
				store.AppendExchange(id, &Exchange{
					RequestID: fmt.Sprintf("request-%d", i),
					ToolCalls: []ToolCall{{ID: fmt.Sprintf("tool-%d", i), Name: "view"}},
				})
				store.UpdateConversation(id, fmt.Sprintf("title-%d", i), nil)
				store.AddCreditUsage(1)
				_ = store.ListConversations("workspace")
				_ = store.ListExchanges(id, 10)
			}
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < exchangesPerWorker; i++ {
			if err := store.flush(); err != nil {
				t.Errorf("flush %d: %v", i, err)
				return
			}
		}
	}()
	wg.Wait()

	if err := store.Close(); err != nil {
		t.Fatalf("close concurrent store: %v", err)
	}
	restarted := NewWithSnapshot(path)
	t.Cleanup(func() { _ = restarted.Close() })
	for worker := 0; worker < workers; worker++ {
		id := fmt.Sprintf("conv-%d", worker)
		if got := len(restarted.ListExchanges(id, 0)); got != exchangesPerWorker {
			t.Fatalf("%s exchanges = %d, want %d", id, got, exchangesPerWorker)
		}
	}
	if got, want := restarted.CreditsUsed(), float64(workers*exchangesPerWorker); got != want {
		t.Fatalf("credits = %v, want %v", got, want)
	}
}

func conversationIDs(conversations []*Conversation) []string {
	ids := make([]string, len(conversations))
	for i, conversation := range conversations {
		ids[i] = conversation.ID
	}
	return ids
}
