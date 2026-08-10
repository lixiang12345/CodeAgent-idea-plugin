// Package state holds the tenant's in-memory session data: conversations,
// chat history exchanges, recorded events and credit counters.  Persistence is
// done through periodic JSON snapshots to disk so conversations survive
// container restarts.
package state

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

const defaultSaveInterval = 5 * time.Second

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
	RequestID    string     `json:"request_id"`
	RequestMsg   string     `json:"request_message"`
	ResponseText string     `json:"response_text"`
	ToolCalls    []ToolCall `json:"tool_calls,omitempty"`
	TurnID       string     `json:"turn_id,omitempty"`
	CreatedAt    time.Time  `json:"created_at"`
	IsToolTurn   bool       `json:"is_tool_turn,omitempty"`
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
	persistMu     sync.Mutex
	conversations map[string]*Conversation
	exchanges     map[string][]*Exchange // conversation_id -> ordered
	creditsUsed   float64
	events        []map[string]any // request/session events, cap-bounded
	now           func() time.Time

	snapshotPath string
	dirty        bool
	saveInterval time.Duration
	stopOnce     sync.Once
	stopCh       chan struct{}
	doneCh       chan struct{}
}

// New creates a Store.  If snapshotPath is non-empty, the store attempts to
// load a previous snapshot and launches a background goroutine that flushes
// changes to disk every few seconds.
func New() *Store {
	return NewWithSnapshot("")
}

// NewWithSnapshot creates a Store with file-backed persistence.
func NewWithSnapshot(snapshotPath string) *Store {
	return newWithSnapshot(snapshotPath, defaultSaveInterval)
}

func newWithSnapshot(snapshotPath string, saveInterval time.Duration) *Store {
	if saveInterval <= 0 {
		saveInterval = defaultSaveInterval
	}
	s := &Store{
		conversations: make(map[string]*Conversation),
		exchanges:     make(map[string][]*Exchange),
		now:           time.Now,
		snapshotPath:  snapshotPath,
		saveInterval:  saveInterval,
	}
	if snapshotPath == "" {
		return s
	}
	if err := os.MkdirAll(filepath.Dir(snapshotPath), 0o755); err != nil {
		log.Printf("state: create snapshot directory for %s: %v", snapshotPath, err)
		return s
	}
	if err := s.load(); err != nil && !os.IsNotExist(err) {
		log.Printf("state: load %s: %v", snapshotPath, err)
	}
	s.stopCh = make(chan struct{})
	s.doneCh = make(chan struct{})
	go s.autoSaveLoop()
	return s
}

// Close flushes any pending writes to disk.  Call before shutdown.
func (s *Store) Close() error {
	if s.stopCh != nil {
		s.stopOnce.Do(func() { close(s.stopCh) })
		<-s.doneCh
	}
	return s.flush()
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

func (s *Store) snapshotLocked() snapshot {
	snap := snapshot{
		Conversations: make(map[string]*Conversation, len(s.conversations)),
		Exchanges:     make(map[string][]*Exchange, len(s.exchanges)),
		CreditsUsed:   s.creditsUsed,
	}
	for id, conversation := range s.conversations {
		snap.Conversations[id] = cloneConversation(conversation)
	}
	for id, exchanges := range s.exchanges {
		copied := make([]*Exchange, len(exchanges))
		for i, exchange := range exchanges {
			copied[i] = cloneExchange(exchange)
		}
		snap.Exchanges[id] = copied
	}
	return snap
}

func (s *Store) flush() error {
	s.persistMu.Lock()
	defer s.persistMu.Unlock()

	s.mu.Lock()
	path := s.snapshotPath
	if path == "" || !s.dirty {
		s.mu.Unlock()
		return nil
	}
	snap := s.snapshotLocked()
	s.dirty = false
	s.mu.Unlock()

	if err := writeSnapshot(path, snap); err != nil {
		s.mu.Lock()
		s.dirty = true
		s.mu.Unlock()
		return err
	}
	return nil
}

func writeSnapshot(path string, snap snapshot) error {
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	removeTmp := true
	defer func() {
		if removeTmp {
			_ = os.Remove(tmp)
		}
	}()
	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	if err := enc.Encode(snap); err != nil {
		_ = f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	removeTmp = false
	return nil
}

// markDirty must only be called while s.mu is already held.
func (s *Store) markDirty() {
	s.dirty = true
}

func (s *Store) autoSaveLoop() {
	defer close(s.doneCh)
	tk := time.NewTicker(s.saveInterval)
	defer tk.Stop()
	for {
		select {
		case <-tk.C:
			if err := s.flush(); err != nil {
				log.Printf("state: snapshot write: %v", err)
			}
		case <-s.stopCh:
			return
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
		return cloneConversation(c)
	}
	now := s.now()
	c := &Conversation{ID: id, WorkspaceID: workspaceID, Title: title, IsPinned: pinned,
		CreatedAt: now, UpdatedAt: now}
	s.conversations[id] = c
	s.markDirty()
	return cloneConversation(c)
}

// GetConversation returns a conversation by ID.
func (s *Store) GetConversation(id string) (*Conversation, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.conversations[id]
	return cloneConversation(c), ok
}

// UpdateConversation mutates title/pinned of an existing conversation.
func (s *Store) UpdateConversation(id, title string, pinned *bool) *Conversation {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.conversations[id]
	now := s.now()
	if !ok {
		c = &Conversation{ID: id, CreatedAt: now}
		s.conversations[id] = c
	}
	if title != "" {
		c.Title = title
	}
	if pinned != nil {
		c.IsPinned = *pinned
	}
	c.UpdatedAt = now
	s.markDirty()
	return cloneConversation(c)
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
	sort.Slice(out, func(i, j int) bool {
		if !out[i].UpdatedAt.Equal(out[j].UpdatedAt) {
			return out[i].UpdatedAt.After(out[j].UpdatedAt)
		}
		if !out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].CreatedAt.After(out[j].CreatedAt)
		}
		return out[i].ID < out[j].ID
	})
	return out
}

// AppendExchange records a turn and bumps conversation activity.
func (s *Store) AppendExchange(conversationID string, ex *Exchange) {
	if ex == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	copied := cloneExchange(ex)
	copied.CreatedAt = s.now()
	s.exchanges[conversationID] = append(s.exchanges[conversationID], copied)
	if c, ok := s.conversations[conversationID]; ok {
		c.LastExchangeAt = copied.CreatedAt
		c.UpdatedAt = copied.CreatedAt
	}
	s.markDirty()
}

// ListExchanges returns the recorded turns for a conversation.
func (s *Store) ListExchanges(conversationID string, limit int) []*Exchange {
	s.mu.Lock()
	defer s.mu.Unlock()
	all := s.exchanges[conversationID]
	if limit <= 0 || len(all) <= limit {
		return cloneExchanges(all)
	}
	return cloneExchanges(all[len(all)-limit:])
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
	copied := make(map[string]any, len(evt)+2)
	for key, value := range evt {
		copied[key] = value
	}
	copied["_kind"] = kind
	copied["_ts"] = s.now().UTC().Format(time.RFC3339)
	s.events = append(s.events, copied)
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

func cloneConversation(conversation *Conversation) *Conversation {
	if conversation == nil {
		return nil
	}
	copied := *conversation
	return &copied
}

func cloneExchange(exchange *Exchange) *Exchange {
	if exchange == nil {
		return nil
	}
	copied := *exchange
	copied.ToolCalls = append([]ToolCall(nil), exchange.ToolCalls...)
	return &copied
}

func cloneExchanges(exchanges []*Exchange) []*Exchange {
	copied := make([]*Exchange, len(exchanges))
	for i, exchange := range exchanges {
		copied[i] = cloneExchange(exchange)
	}
	return copied
}
