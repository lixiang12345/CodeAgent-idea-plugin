// Package state holds the tenant's in-memory session data: conversations,
// chat history exchanges, recorded events and credit counters.  Persistence is
// done through periodic JSON snapshots to disk so conversations survive
// container restarts.
package state

import (
	"encoding/json"
	"log"
	"os"
	"strings"
	"sync"
	"time"
)

// Conversation is the tenant-side conversation record.
type Conversation struct {
	ID             string    `json:"conversation_id"`
	WorkspaceID    string    `json:"workspace_id"`
	Title          string    `json:"title"`
	IsPinned       bool      `json:"is_pinned"`
	CreatedAt      time.Time `json:"created_at"`
	UpdatedAt      time.Time `json:"updated_at"`
	LastExchangeAt time.Time `json:"last_exchange_at"`
}

// Exchange is one user→assistant turn.
type Exchange struct {
	RequestID     string     `json:"request_id"`
	RequestMsg    string     `json:"request_message"`
	ResponseText  string     `json:"response_text"`
	ToolCalls     []ToolCall `json:"tool_calls,omitempty"`
	TurnID        string     `json:"turn_id,omitempty"`
	CreatedAt     time.Time  `json:"created_at"`
	IsToolTurn    bool       `json:"is_tool_turn,omitempty"`
}

// ToolCall records one tool invocation from the model.
type ToolCall struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

// snapshot is the on-disk serialisation format.
type snapshot struct {
	Conversations map[string]*Conversation `json:"conversations"`
	Exchanges     map[string][]*Exchange   `json:"exchanges"`
	CreditsUsed   float64                  `json:"credits_used"`
}

// Store is the thread-safe in-memory state with periodic snapshot persistence.
type Store struct {
	mu            sync.Mutex
	conversations map[string]*Conversation
	exchanges     map[string][]*Exchange // conversation_id -> ordered
	creditsUsed   float64
	events        []map[string]any // request/session events, cap-bounded
	now           func() time.Time

	snapshotPath string
	dirty        bool
}

// New creates a Store.  If snapshotPath is non-empty, the store attempts to
// load a previous snapshot and launches a background goroutine that flushes
// changes to disk every few seconds.
func New() *Store {
	return NewWithSnapshot("")
}

// NewWithSnapshot creates a Store with file-backed persistence.
func NewWithSnapshot(snapshotPath string) *Store {
	s := &Store{
		conversations: make(map[string]*Conversation),
		exchanges:     make(map[string][]*Exchange),
		now:           time.Now,
		snapshotPath:  snapshotPath,
	}
	if snapshotPath != "" {
		ready := false
		if idx := strings.LastIndex(snapshotPath, "/"); idx > 0 {
			if err := os.MkdirAll(snapshotPath[:idx], 0o755); err == nil {
				ready = true
			}
		}
		if ready {
			if err := s.load(); err != nil && !os.IsNotExist(err) {
				log.Printf("state: load %s: %v", snapshotPath, err)
			}
			go s.autoSaveLoop()
		}
	}
	return s
}

// Close flushes any pending writes to disk.  Call before shutdown.
func (s *Store) Close() error {
	s.mu.Lock()
	path := s.snapshotPath
	dirty := s.dirty
	s.dirty = false
	s.mu.Unlock()
	if !dirty || path == "" {
		return nil
	}
	return s.writeSnapshot(path)
}

// ── persistence ────────────────────────────────────────────────────────

func (s *Store) load() error {
	f, err := os.Open(s.snapshotPath)
	if err != nil {
		return err
	}
	defer f.Close()
	var snap snapshot
	if err := json.NewDecoder(f).Decode(&snap); err != nil {
		return err
	}
	s.mu.Lock()
	s.conversations = snap.Conversations
	s.exchanges = snap.Exchanges
	s.creditsUsed = snap.CreditsUsed
	if s.conversations == nil {
		s.conversations = make(map[string]*Conversation)
	}
	if s.exchanges == nil {
		s.exchanges = make(map[string][]*Exchange)
	}
	s.mu.Unlock()
	log.Printf("state: loaded %d conversations from %s", len(snap.Conversations), s.snapshotPath)
	return nil
}

func (s *Store) writeSnapshot(path string) error {
	s.mu.Lock()
	snap := snapshot{
		Conversations: s.conversations,
		Exchanges:     s.exchanges,
		CreditsUsed:   s.creditsUsed,
	}
	s.mu.Unlock()

	tmp := path + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	if err := enc.Encode(snap); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	f.Close()
	return os.Rename(tmp, path)
}

// markDirty must only be called while s.mu is already held.
func (s *Store) markDirty() {
	s.dirty = true
}

func (s *Store) autoSaveLoop() {
	tk := time.NewTicker(5 * time.Second)
	defer tk.Stop()
	for range tk.C {
		s.mu.Lock()
		dirty := s.dirty
		s.dirty = false
		path := s.snapshotPath
		s.mu.Unlock()
		if !dirty {
			continue
		}
		if err := s.writeSnapshot(path); err != nil {
			log.Printf("state: snapshot write: %v", err)
		}
	}
}

func (s *Store) Now() time.Time { return s.now() }

// CreateConversation stores a new conversation (or returns the existing one
// when the client supplies a pre-generated conversation_id).
func (s *Store) CreateConversation(id, workspaceID, title string, pinned bool) *Conversation {
	s.mu.Lock()
	defer s.mu.Unlock()
	if c, ok := s.conversations[id]; ok {
		return c
	}
	c := &Conversation{ID: id, WorkspaceID: workspaceID, Title: title, IsPinned: pinned,
		CreatedAt: s.now(), UpdatedAt: s.now()}
	s.conversations[id] = c
	s.markDirty()
	return c
}

// GetConversation returns a conversation by ID.
func (s *Store) GetConversation(id string) (*Conversation, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.conversations[id]
	return c, ok
}

// UpdateConversation mutates title/pinned of an existing conversation.
func (s *Store) UpdateConversation(id, title string, pinned *bool) *Conversation {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.conversations[id]
	if !ok {
		c = &Conversation{ID: id, CreatedAt: s.now()}
		s.conversations[id] = c
	}
	if title != "" {
		c.Title = title
	}
	if pinned != nil {
		c.IsPinned = *pinned
	}
	c.UpdatedAt = s.now()
	s.markDirty()
	return c
}

// ListConversations returns conversations newest-first.
func (s *Store) ListConversations(workspaceID string) []*Conversation {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]*Conversation, 0, len(s.conversations))
	for _, c := range s.conversations {
		if workspaceID != "" && c.WorkspaceID != workspaceID && c.WorkspaceID != "" {
			continue
		}
		cp := *c
		out = append(out, &cp)
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out
}

// AppendExchange records a turn and bumps conversation activity.
func (s *Store) AppendExchange(conversationID string, ex *Exchange) {
	s.mu.Lock()
	defer s.mu.Unlock()
	ex.CreatedAt = s.now()
	s.exchanges[conversationID] = append(s.exchanges[conversationID], ex)
	if c, ok := s.conversations[conversationID]; ok {
		c.LastExchangeAt = ex.CreatedAt
		c.UpdatedAt = ex.CreatedAt
	}
	s.markDirty()
}

// ListExchanges returns the recorded turns for a conversation.
func (s *Store) ListExchanges(conversationID string, limit int) []*Exchange {
	s.mu.Lock()
	defer s.mu.Unlock()
	all := s.exchanges[conversationID]
	if limit <= 0 || len(all) <= limit {
		out := make([]*Exchange, len(all))
		copy(out, all)
		return out
	}
	out := make([]*Exchange, limit)
	copy(out, all[len(all)-limit:])
	return out
}

// AddCreditUsage increments the credit ledger.
func (s *Store) AddCreditUsage(delta float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.creditsUsed += delta
	s.markDirty()
}

// CreditsUsed returns total recorded credit consumption.
func (s *Store) CreditsUsed() float64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.creditsUsed
}

// RecordEvent appends a telemetry event (request/session) to the bounded log.
func (s *Store) RecordEvent(kind string, evt map[string]any) {
	s.mu.Lock()
	defer s.mu.Unlock()
	evt["_kind"] = kind
	evt["_ts"] = s.now().UTC().Format(time.RFC3339)
	s.events = append(s.events, evt)
	if len(s.events) > 2000 {
		s.events = s.events[len(s.events)-2000:]
	}
}

// EventCount returns how many telemetry events have been recorded.
func (s *Store) EventCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.events)
}
