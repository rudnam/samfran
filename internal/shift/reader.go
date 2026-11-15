package shift

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/anthropics/anthropic-sdk-go/v2"
	"github.com/anthropics/anthropic-sdk-go/v2/option"
)

// Sheet is what was read from a photo of a shift sheet: one person's row.
// Nothing else on the sheet (other people's names and shifts) is kept.
type Sheet struct {
	// Found is false if the person's row couldn't be found.
	Found bool `json:"found"`
	// NameAsRead is the name on the row that was read, as written.
	NameAsRead string `json:"name_as_read"`
	Year       int    `json:"year"`
	Month      int    `json:"month"`
	Days       []struct {
		Day  int    `json:"day"`
		Code string `json:"code"`
	} `json:"days"`
	// Notes are the reader's doubts, e.g. a cell it couldn't make out.
	Notes string `json:"notes"`
}

// Reader reads one person's row from a photo of a shift sheet.
type Reader interface {
	Read(ctx context.Context, image []byte, mediaType, name string, hint Month) (Sheet, error)
}

// Month is a year and month, the one the sheet probably shows.
type Month struct{ Year, Month int }

// ErrRefused is a request the model declined to answer.
var ErrRefused = errors.New("the photo couldn't be read")

// Claude reads sheets with the Claude API (vision and structured outputs).
type Claude struct {
	client anthropic.Client
	model  string
}

func NewClaude(apiKey, model string) *Claude {
	if model == "" {
		model = "claude-opus-5-5"
	}
	return &Claude{client: anthropic.NewClient(option.WithAPIKey(apiKey)), model: model}
}

const systemPrompt = `You read photos of printed monthly work schedules (勤務表) from a Japanese workplace. The sheet is a grid: one row per employee (an employee number and a name, often in katakana), one column per day of the month (1, 2, 3…), and a code in each cell.

Codes you'll see: a start hour and a letter for a working day, e.g. 7F, 9D, 12G, 13F, 22F; 普 and 法 for days off; 有休 for paid leave; トレ and 研修 for training. A cell can hold a second, smaller note (often 有休) under the code: when it does, report the cell's main code and mention the note in notes.

Read only the one row you're asked for, every day of the month, left to right. Copy each code exactly as printed, with ASCII digits and letters. Photos are often taken at an angle with highlighter on them; follow the row carefully across the page and don't drift into the row above or below. If a cell is empty, give an empty code. If you can't make out a cell, give your best reading and say which day in notes.

Never report anyone else's name or shifts.`

var sheetSchema = map[string]any{
	"type": "object",
	"properties": map[string]any{
		"found":        map[string]any{"type": "boolean", "description": "Whether the person's row was found"},
		"name_as_read": map[string]any{"type": "string", "description": "The name on the row read, as printed"},
		"year":         map[string]any{"type": "integer"},
		"month":        map[string]any{"type": "integer", "description": "1 to 12"},
		"days": map[string]any{
			"type": "array",
			"items": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"day":  map[string]any{"type": "integer"},
					"code": map[string]any{"type": "string"},
				},
				"required":             []string{"day", "code"},
				"additionalProperties": false,
			},
		},
		"notes": map[string]any{"type": "string", "description": "Doubts, e.g. a cell you couldn't make out; empty if none"},
	},
	"required":             []string{"found", "name_as_read", "year", "month", "days", "notes"},
	"additionalProperties": false,
}

func (c *Claude) Read(ctx context.Context, image []byte, mediaType, name string, hint Month) (Sheet, error) {
	prompt := fmt.Sprintf("Read the row for %q. The sheet is probably for %d-%02d (check its title, e.g. \"2026年 11月度\").", name, hint.Year, hint.Month)
	resp, err := c.client.Beta.Messages.New(ctx, anthropic.BetaMessageNewParams{
		Model:     c.model,
		MaxTokens: 16000,
		// A refusal is re-served by another model in the same call.
		Betas:     []anthropic.AnthropicBeta{anthropic.AnthropicBetaServerSideFallback2026_07_01},
		Fallbacks: anthropic.BetaFallbacksParamOfDefault(),
		System:    []anthropic.BetaTextBlockParam{{Text: systemPrompt}},
		OutputConfig: anthropic.BetaOutputConfigParam{
			Effort: "high", // a dense grid: worth reading carefully
			Format: anthropic.BetaJSONOutputFormatParam{Schema: sheetSchema},
		},
		Messages: []anthropic.BetaMessageParam{
			anthropic.NewBetaUserMessage(
				anthropic.NewBetaImageBlock(anthropic.BetaBase64ImageSourceParam{
					Data:      base64.StdEncoding.EncodeToString(image),
					MediaType: anthropic.BetaBase64ImageSourceMediaType(mediaType),
				}),
				anthropic.NewBetaTextBlock(prompt),
			),
		},
	})
	if err != nil {
		return Sheet{}, fmt.Errorf("read shift sheet: %w", err)
	}
	if resp.StopReason == anthropic.BetaStopReasonRefusal {
		return Sheet{}, ErrRefused
	}
	var text strings.Builder
	for _, block := range resp.Content {
		if t, ok := block.AsAny().(anthropic.BetaTextBlock); ok {
			text.WriteString(t.Text)
		}
	}
	var sheet Sheet
	if err := json.Unmarshal([]byte(text.String()), &sheet); err != nil {
		return Sheet{}, fmt.Errorf("read shift sheet: decode (stop %s): %w", resp.StopReason, err)
	}
	return sheet, nil
}
