package surface

import (
	"time"

	"augment-local/internal/chat"
	"augment-local/internal/state"
)

// chatUnary answers the unary Chat RPC with a single ChatResponse carrying the
// same node skeleton the streaming path uses.
func (r *Responder) chatUnary(req map[string]any) (any, error) {
	question, _ := req["message"].(string)
	convID, _ := req["conversation_id"].(string)
	sim := chat.New(r.Store, r.GatewayURL, "")
	var nodes []any
	text := sim.Nodes(question, &nodes)
	requestID, _ := req["turn_id"].(string)
	if requestID == "" {
		requestID = "req-unary-" + time.Now().UTC().Format("20060102T150405")
	}
	r.Store.AppendExchange(convID, &state.Exchange{
		RequestID: requestID, RequestMsg: question, ResponseText: text,
	})
	r.Store.AddCreditUsage(0.01)
	return map[string]any{
		"text":        text,
		"nodes":       nodes,
		"stop_reason": "END_TURN",
	}, nil
}
