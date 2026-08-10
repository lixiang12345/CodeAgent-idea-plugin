// Package state holds the tenant's in-memory session data: conversations,
// chat history exchanges, recorded events and credit counters. All of it is
// volatile; a Postgres-backed store can replace it later without touching the
// handler layer.
package state

import (
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

// Store is the thread-safe in-memory state.
type Store struct {
	mu            sync.Mutex
	conversations map[string]*Conversation
	exchanges     map[string][]*Exchange // conversation_id -> ordered
	creditsUsed   float64
	events        []map[string]any // request/session events, cap-bounded
	now           func() time.Time
}

func New() *Store {
	return &Store{
		conversations: make(map[string]*Conversation),
		exchanges:     make(map[string][]*Exchange),
		now:           time.Now,
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
