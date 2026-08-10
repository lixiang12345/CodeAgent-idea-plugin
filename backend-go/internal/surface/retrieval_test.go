package surface

import (
	"strings"
	"testing"

	"augment-local/internal/state"
	"augment-local/internal/tools"
)

func TestCodebaseRetrievalNeverReturnsAnEmptySuccessfulResult(t *testing.T) {
	executor := tools.New(t.TempDir())
	executor.ContextEngine.URL = ""
	responder := &Responder{Store: state.New(), ToolExecutor: executor}

	response, handled, err := responder.Handle("CodebaseRetrieval", map[string]any{
		"information_request": "a symbol that is not present",
		"conversation_id":     "conversation-1",
	})
	if err != nil || !handled {
		t.Fatalf("codebase retrieval handled=%v err=%v", handled, err)
	}
	formatted := response.(map[string]any)["formatted_retrieval"].(string)
	if strings.TrimSpace(formatted) == "" {
		t.Fatalf("codebase retrieval returned an empty success: %#v", response)
	}
}
