package surface

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

func (r *Responder) completion(req map[string]any) (any, error) {
	empty := func() map[string]any {
		return map[string]any{
			"text":                        "",
			"unknown_memory_names":        []any{},
			"suggested_prefix_char_count": 0,
			"suggested_suffix_char_count": 0,
			"completion_items":            []any{},
			"checkpoint_not_found":        false,
		}
	}
	if probe, _ := req["probe_only"].(bool); probe {
		return empty(), nil
	}

	prefix := boundedRunes(asString(req["prompt"]), 16*1024, true)
	suffix := boundedRunes(asString(req["suffix"]), 4*1024, false)
	if (prefix == "" && suffix == "") || r.GatewayURL == "" {
		return empty(), nil
	}
	model := strings.TrimSpace(asString(req["model"]))
	if model == "" {
		model = r.GatewayModel
	}
	if model == "" {
		return empty(), nil
	}

	maxTokens := completionMaxTokens(req["max_tokens"])
	path := boundedRunes(asString(req["path"]), 1024, false)
	lang := boundedRunes(asString(req["lang"]), 128, false)
	userContent := "Code before cursor:\n" + prefix + "\n\nCode after cursor:\n" + suffix
	if path != "" {
		userContent = "File: " + path + "\n" + userContent
	}
	if lang != "" {
		userContent = "Language: " + lang + "\n" + userContent
	}
	body, err := json.Marshal(map[string]any{
		"model": model,
		"messages": []map[string]any{
			{"role": "system", "content": "Complete code at the cursor. Return only the code to insert, with no markdown fences or explanation."},
			{"role": "user", "content": userContent},
		},
		"reasoning_effort": "low",
		"temperature":      0.1,
		"max_tokens":       maxTokens,
		"stream":           false,
	})
	if err != nil {
		return empty(), nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, openAIChatURL(r.GatewayURL), bytes.NewReader(body))
	if err != nil {
		return empty(), nil
	}
	request.Header.Set("Content-Type", "application/json")
	if key := os.Getenv("MODEL_GATEWAY_API_KEY"); key != "" {
		request.Header.Set("Authorization", "Bearer "+key)
	}
	client := r.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 3 * time.Second}
	}
	response, err := client.Do(request)
	if err != nil {
		log.Printf("surface: code completion unavailable: %v", err)
		return empty(), nil
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		log.Printf("surface: code completion gateway status=%d", response.StatusCode)
		return empty(), nil
	}

	var out struct {
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	decoder := json.NewDecoder(io.LimitReader(response.Body, 256*1024))
	if err := decoder.Decode(&out); err != nil || out.Error.Message != "" || len(out.Choices) == 0 {
		log.Printf("surface: code completion gateway returned no usable choice")
		return empty(), nil
	}
	text := boundedRunes(strings.TrimSpace(out.Choices[0].Message.Content), maxTokens*8, false)
	if text == "" {
		return empty(), nil
	}
	return map[string]any{
		"text":                        text,
		"unknown_memory_names":        []any{},
		"suggested_prefix_char_count": 0,
		"suggested_suffix_char_count": 0,
		"completion_items": []any{map[string]any{
			"text":                    text,
			"skipped_suffix":          "",
			"suffix_replacement_text": "",
			"filter_score":            1.0,
		}},
		"checkpoint_not_found": false,
	}, nil
}

func completionMaxTokens(value any) int {
	maxTokens := 128
	switch v := value.(type) {
	case float64:
		maxTokens = int(v)
	case int:
		maxTokens = v
	case int32:
		maxTokens = int(v)
	case int64:
		maxTokens = int(v)
	}
	if maxTokens < 1 {
		return 1
	}
	if maxTokens > 512 {
		return 512
	}
	return maxTokens
}

func (r *Responder) resolveCompletions(_ map[string]any) (any, error) {
	return map[string]any{}, nil
}
