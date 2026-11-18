package shift

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/anthropics/anthropic-sdk-go/v2"
	"github.com/anthropics/anthropic-sdk-go/v2/option"
)

// Sheet is what was read from a photo of a shift sheet: one person's row.
// Nothing else on the sheet (other people's names and shifts) is kept.
type Sheet struct {
	// Found is false if the person's row couldn't be found.
	Found bool `json:"found"`
	// NameAsRead is the name on the row that was read, as written.
	NameAsRead string     `json:"name_as_read"`
	Year       int        `json:"year"`
	Month      int        `json:"month"`
	Days       []SheetDay `json:"days"`
	// Notes are the reader's doubts, e.g. a cell it couldn't make out.
	Notes string `json:"notes"`
}

// SheetDay is one cell of the row: the code and, if there is one, the
// smaller note printed under it.
type SheetDay struct {
	Day  int    `json:"day"`
	Code string `json:"code"`
	Note string `json:"note"`
	// With are the others whose cell that day has exactly the same code.
	With []string `json:"with"`
	// Others are everyone else working on and around the day (Coworker), for
	// who's there during the shift. The reader's own lists are Same,
	// NightBefore and MorningAfter; Read files them under With and Others.
	Others []Coworker `json:"-"`
	// What the model answers (see sheetSchema).
	Same         []Coworker `json:"others"`
	NightBefore  []Coworker `json:"night_before"`
	MorningAfter []Coworker `json:"morning_after"`
}

// CellCode is the code a cell stands for. A small 有休 under a shift's code
// means the shift was taken as paid leave, so the day is 有休.
func CellCode(code, note string) string {
	switch normalize(note) {
	case "有休", "有", "有給":
		return "有休"
	}
	return code
}

// Reader reads one person's row from a photo of a shift sheet.
type Reader interface {
	Read(ctx context.Context, image []byte, mediaType, name string, hint Month) (Sheet, error)
}

// Month is what's known before reading: the year and month the sheet
// probably shows, and the names of coworkers already saved, so the reader can
// pick them over a close lookalike.
type Month struct {
	Year, Month int
	Names       []string
	// Progress, if set, is told how the read is going as it goes.
	Progress func(Progress)
}

// Progress is how far a read has got, for the apps to show while they wait.
type Progress struct {
	// NameAsRead is the name on the row, once it's been found.
	NameAsRead string `json:"name_as_read,omitempty"`
	// DaysRead of DaysInMonth have been read (both 0 until the days start).
	DaysRead    int `json:"days_read"`
	DaysInMonth int `json:"days_in_month"`
}

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

Codes you'll see: a start hour and a letter for a working day, e.g. 7F, 9D, 12G, 13F, 22F; 普 and 法 for days off; 有休 for paid leave; トレ and 研修 for training. A cell can hold a second, smaller note (often 有休) under the code: when it does, report the cell's main code in code and the small note, exactly as printed, in note. Leave note empty when there's none.

Read only the one row you're asked for, every day of the month, left to right. Copy each code exactly as printed, with ASCII digits and letters. Photos are often taken at an angle with highlighter on them; follow the row carefully across the page and don't drift into the row above or below. If a cell is empty, give an empty code. If you can't make out a cell, give your best reading and say which day in notes.

For each day the person works (a code like 13F), say who else is at work around their shift, each with their name as printed and the code in their cell:
- others: everyone else whose cell that same day is a working code (a start hour and a letter).
- night_before: everyone whose cell on the day before is a working code starting at 15 or later, since it may run past midnight into this day. Give the code from the day before. Empty for day 1.
- morning_after: only when the person's own code that day starts at 15 or later, everyone whose cell on the next day is a working code starting before 12, since the person's shift may run into it. Give the code from the next day. Empty otherwise, and for the month's last day.
Leave out anyone whose cell has a small 有休 under it, and anyone off. Leave all three lists empty on days the person doesn't work. Never report anything else about anyone else: not their days off, their other days or their employee numbers.`

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
					"day":           map[string]any{"type": "integer"},
					"code":          map[string]any{"type": "string"},
					"note":          map[string]any{"type": "string", "description": "The smaller note under the code, e.g. 有休; empty if none"},
					"others":        othersSchema("Everyone else working that same day; empty on days off"),
					"night_before":  othersSchema("Everyone working the day before on a code starting at 15 or later; empty on days off and day 1"),
					"morning_after": othersSchema("If the person's code starts at 15 or later: everyone working the next day on a code starting before 12; else empty"),
				},
				"required":             []string{"day", "code", "note", "others", "night_before", "morning_after"},
				"additionalProperties": false,
			},
		},
		"notes": map[string]any{"type": "string", "description": "Doubts, e.g. a cell you couldn't make out; empty if none"},
	},
	"required":             []string{"found", "name_as_read", "year", "month", "days", "notes"},
	"additionalProperties": false,
}

func othersSchema(description string) map[string]any {
	return map[string]any{
		"type":        "array",
		"description": description,
		"items": map[string]any{
			"type": "object",
			"properties": map[string]any{
				"name": map[string]any{"type": "string", "description": "As printed"},
				"code": map[string]any{"type": "string", "description": "The code in their cell that day, e.g. 9D"},
			},
			"required":             []string{"name", "code"},
			"additionalProperties": false,
		},
	}
}

// fileOthers sorts what the model listed for each day: those with exactly
// the person's code under With, everyone else under Others, by which day
// their cell is on.
func fileOthers(sheet *Sheet) {
	for i := range sheet.Days {
		d := &sheet.Days[i]
		for _, o := range d.Same {
			if normalize(o.Code) == normalize(d.Code) {
				d.With = append(d.With, o.Name)
			} else {
				d.Others = append(d.Others, Coworker{Name: o.Name, Code: o.Code})
			}
		}
		for _, o := range d.NightBefore {
			d.Others = append(d.Others, Coworker{Name: o.Name, Code: o.Code, DayOffset: -1})
		}
		for _, o := range d.MorningAfter {
			d.Others = append(d.Others, Coworker{Name: o.Name, Code: o.Code, DayOffset: 1})
		}
		d.Same, d.NightBefore, d.MorningAfter = nil, nil, nil
	}
}

var (
	progressName  = regexp.MustCompile(`"name_as_read"\s*:\s*"((?:[^"\\]|\\.)*)"`)
	progressYear  = regexp.MustCompile(`"year"\s*:\s*(\d+)`)
	progressMonth = regexp.MustCompile(`"month"\s*:\s*(\d+)`)
	progressDay   = regexp.MustCompile(`"day"\s*:`)
)

// progressOf is how far the answer so far has got. The answer is JSON in the
// schema's order, so the name comes first and then the days one by one.
func progressOf(text string, hint Month) Progress {
	var p Progress
	if m := progressName.FindStringSubmatch(text); m != nil {
		_ = json.Unmarshal([]byte(`"`+m[1]+`"`), &p.NameAsRead)
	}
	year, month := hint.Year, hint.Month
	if m := progressYear.FindStringSubmatch(text); m != nil {
		year, _ = strconv.Atoi(m[1])
	}
	if m := progressMonth.FindStringSubmatch(text); m != nil {
		month, _ = strconv.Atoi(m[1])
	}
	if month >= 1 && month <= 12 && year > 2000 {
		p.DaysInMonth = time.Date(year, time.Month(month)+1, 0, 0, 0, 0, 0, time.UTC).Day()
	}
	// The day being read now isn't done until the next one starts.
	p.DaysRead = max(0, len(progressDay.FindAllStringIndex(text, -1))-1)
	if p.DaysInMonth > 0 {
		p.DaysRead = min(p.DaysRead, p.DaysInMonth)
	}
	return p
}

func (c *Claude) Read(ctx context.Context, image []byte, mediaType, name string, hint Month) (Sheet, error) {
	prompt := fmt.Sprintf("Read the row for %q. The sheet is probably for %d-%02d (check its title, e.g. \"2026年 11月度\").", name, hint.Year, hint.Month)
	if len(hint.Names) > 0 {
		prompt += "\n\nPeople on earlier sheets (spelling already checked): " + strings.Join(hint.Names, "、") +
			". When a name in with is almost one of these, use this spelling; if it's clearly someone else, write it as printed."
	}
	// Streamed, so the apps can show the days as they're read.
	stream := c.client.Beta.Messages.NewStreaming(ctx, anthropic.BetaMessageNewParams{
		Model:     c.model,
		MaxTokens: 32000,
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
	defer stream.Close()
	var resp anthropic.BetaMessage
	var sofar strings.Builder
	var last Progress
	for stream.Next() {
		event := stream.Current()
		if err := resp.Accumulate(event); err != nil {
			return Sheet{}, fmt.Errorf("read shift sheet: %w", err)
		}
		if hint.Progress == nil || event.Type != "content_block_delta" {
			continue
		}
		if event.Delta.Type == "text_delta" {
			sofar.WriteString(event.Delta.Text)
			if p := progressOf(sofar.String(), hint); p != last {
				last = p
				hint.Progress(p)
			}
		}
	}
	if err := stream.Err(); err != nil {
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
	fileOthers(&sheet)
	return sheet, nil
}
