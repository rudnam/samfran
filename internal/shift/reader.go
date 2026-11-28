package shift

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"strconv"
	"strings"
	"sync"
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
	// Who else is there, as the model answers it (see peopleSchema).
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
	// RowDone: the person's row is read, and WorkDays of its days are
	// working days. Who else is there is read alongside, and PeopleRead of
	// those days are done; the read answers once they all are (or peopleWait).
	RowDone    bool `json:"row_done"`
	WorkDays   int  `json:"work_days"`
	PeopleRead int  `json:"people_read"`
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

// The sheet is read by two calls to Claude side by side: one reads the
// person's own row (small, so it's quick), the other who else is at work
// around their shifts (long on a sheet with many people: on December 2026's
// 22-person sheet one call doing both ran past readTimeout). The row is what
// counts; the coworkers are added if they arrive in time.

const sheetIntro = `You read photos of printed monthly work schedules (勤務表) from a Japanese workplace. The sheet is a grid: one row per employee (an employee number and a name, often in katakana), one column per day of the month (1, 2, 3…), and a code in each cell.

Codes you'll see: a working day is a start hour and one or more letters, sometimes with letters before the hour, e.g. 7F, 9D, 13F, 22F, or 9LE, 13LE, 7OE, HPA 13LE (different people make the sheet, so either style can appear). 普 and 法 are days off; 有休 is paid leave; トレ and 研修 are training. A cell can hold a second, smaller note (often 有休) under the code. Copy each code exactly as printed, with ASCII digits and letters, including any letters before the hour. Photos are often taken at an angle with highlighter on them; follow each row carefully across the page and don't drift into the row above or below.`

const rowPrompt = sheetIntro + `

Read only the one row you're asked for, every day of the month, left to right. When a cell has a smaller note under the code, report the cell's main code in code and the small note, exactly as printed, in note; leave note empty when there's none. If a cell is empty, give an empty code. If you can't make out a cell, give your best reading and say which day in notes. Never report anything about anyone else.`

const peoplePrompt = sheetIntro + `

Find the one row you're asked for. For each day that person works (a working code), say who else is at work around their shift, each with their name as printed and the code in their cell:
- others: everyone else whose cell that same day is a working code.
- night_before: everyone whose cell on the day before is a working code starting at 15 or later, since it may run past midnight into this day. Give the code from the day before. Empty for day 1.
- morning_after: only when the person's own code that day starts at 15 or later, everyone whose cell on the next day is a working code starting before 12, since the person's shift may run into it. Give the code from the next day. Empty otherwise, and for the month's last day.
Leave out anyone whose cell has a small 有休 under it, and anyone off. List only the days the person works. Never report anything else about anyone else: not their days off, their other days or their employee numbers.`

var rowSchema = map[string]any{
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
					"note": map[string]any{"type": "string", "description": "The smaller note under the code, e.g. 有休; empty if none"},
				},
				"required":             []string{"day", "code", "note"},
				"additionalProperties": false,
			},
		},
		"notes": map[string]any{"type": "string", "description": "Doubts, e.g. a cell you couldn't make out; empty if none"},
	},
	"required":             []string{"found", "name_as_read", "year", "month", "days", "notes"},
	"additionalProperties": false,
}

var peopleSchema = map[string]any{
	"type": "object",
	"properties": map[string]any{
		"days": map[string]any{
			"type": "array",
			"items": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"day":           map[string]any{"type": "integer"},
					"others":        othersSchema("Everyone else working that same day"),
					"night_before":  othersSchema("Everyone working the day before on a code starting at 15 or later; empty for day 1"),
					"morning_after": othersSchema("If the person's code starts at 15 or later: everyone working the next day on a code starting before 12; else empty"),
				},
				"required":             []string{"day", "others", "night_before", "morning_after"},
				"additionalProperties": false,
			},
		},
	},
	"required":             []string{"days"},
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
	// The days are over once the answer moves on to the notes after them.
	progressNotes = regexp.MustCompile(`\]\s*,\s*"notes"\s*:`)
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
	// The day being read now isn't done until the next one starts, or, for
	// the last day, until the notes do; else the bar stops a day short.
	days := len(progressDay.FindAllStringIndex(text, -1))
	p.DaysRead = max(0, days-1)
	if progressNotes.MatchString(text) {
		p.DaysRead = days
	}
	if p.DaysInMonth > 0 {
		p.DaysRead = min(p.DaysRead, p.DaysInMonth)
	}
	return p
}

func (c *Claude) Read(ctx context.Context, image []byte, mediaType, name string, hint Month) (Sheet, error) {
	start := time.Now()
	prompt := fmt.Sprintf("Read the row for %q. The sheet is probably for %d-%02d (check its title, e.g. \"2026年 11月度\").", name, hint.Year, hint.Month)

	// Who else is there: started first, as it's the longer read.
	peopleCtx, stopPeople := context.WithCancel(ctx)
	defer stopPeople()
	type peopleResult struct {
		days []SheetDay
		err  error
	}
	// Both reads tell how far they've got: the row's days, then (once the
	// row's done, as the read waits for it) the coworkers' days.
	var mu sync.Mutex
	var row, last Progress
	peopleRead := 0
	report := func() { // with mu held
		if hint.Progress == nil {
			return
		}
		p := row
		if p.RowDone {
			p.PeopleRead = min(peopleRead, p.WorkDays)
		}
		if p != last {
			last = p
			hint.Progress(p)
		}
	}
	peopleDone := make(chan peopleResult, 1)
	go func() {
		p := prompt
		if len(hint.Names) > 0 {
			p += "\n\nPeople on earlier sheets (spelling already checked): " + strings.Join(hint.Names, "、") +
				". When a name is almost one of these, use this spelling; if it's clearly someone else, write it as printed."
		}
		// Medium effort: it's a lot to write, and only the row has to be exact.
		text, err := c.ask(peopleCtx, peoplePrompt, p, image, mediaType, peopleSchema, "medium", func(sofar string) {
			mu.Lock()
			defer mu.Unlock()
			// As for the row: a day is done once the next one starts.
			peopleRead = max(0, len(progressDay.FindAllStringIndex(sofar, -1))-1)
			report()
		})
		var out struct {
			Days []SheetDay `json:"days"`
		}
		if err == nil {
			err = json.Unmarshal([]byte(text), &out)
		}
		if err == nil {
			mu.Lock()
			peopleRead = len(out.Days)
			report()
			mu.Unlock()
		}
		peopleDone <- peopleResult{out.Days, err}
	}()

	onText := func(sofar string) {
		mu.Lock()
		defer mu.Unlock()
		row = progressOf(sofar, hint)
		report()
	}
	text, err := c.ask(ctx, rowPrompt, prompt, image, mediaType, rowSchema, "high", onText)
	if err != nil {
		return Sheet{}, err
	}
	var sheet Sheet
	if err := json.Unmarshal([]byte(text), &sheet); err != nil {
		return Sheet{}, fmt.Errorf("read shift sheet: decode: %w", err)
	}
	if !sheet.Found {
		return sheet, nil
	}
	mu.Lock()
	row.RowDone, row.DaysRead, row.WorkDays = true, row.DaysInMonth, workDays(sheet.Days)
	report()
	mu.Unlock()

	// The coworkers, if they come in time: by peopleWait after the start (or
	// at once, if the row took longer), and before the read has to answer.
	wait := min(time.Until(start.Add(peopleWait)), time.Until(deadlineOf(ctx))-peopleMargin)
	timer := time.NewTimer(max(wait, 0))
	defer timer.Stop()
	var people peopleResult
	select {
	case people = <-peopleDone:
	case <-timer.C:
		people.err = context.DeadlineExceeded
	}
	if people.err != nil {
		slog.WarnContext(ctx, "read shift sheet: coworkers", "err", people.err)
		sheet.Notes = strings.TrimSpace(sheet.Notes + "\nWho else is working couldn't be read this time.")
	} else {
		addPeople(&sheet, people.days)
	}
	fileOthers(&sheet)
	return sheet, nil
}

// peopleWait is how long after the start a read waits for the coworkers:
// inside the 5 minutes app versions from before December 2026 wait, so
// those still get the row.
const peopleWait = 4*time.Minute + 30*time.Second

// peopleMargin is how long before the read's deadline it stops waiting for
// the coworkers, to answer with the row in time.
const peopleMargin = 5 * time.Second

func deadlineOf(ctx context.Context) time.Time {
	if d, ok := ctx.Deadline(); ok {
		return d
	}
	return time.Now().Add(readTimeout)
}

// workDays is how many of days are working days, the ones whose coworkers
// are read (addPeople).
func workDays(days []SheetDay) int {
	n := 0
	for _, d := range days {
		if workCode.MatchString(normalize(CellCode(d.Code, d.Note))) {
			n++
		}
	}
	return n
}

// addPeople puts who else is there, as read on its own, on the row's days.
// Days the row says the person doesn't work get no one.
func addPeople(sheet *Sheet, days []SheetDay) {
	byDay := map[int]SheetDay{}
	for _, d := range days {
		byDay[d.Day] = d
	}
	for i := range sheet.Days {
		d := &sheet.Days[i]
		if !workCode.MatchString(normalize(CellCode(d.Code, d.Note))) {
			continue
		}
		p := byDay[d.Day]
		d.Same, d.NightBefore, d.MorningAfter = p.Same, p.NightBefore, p.MorningAfter
	}
}

// ask streams one answer in schema's shape, telling onText (if not nil) the
// text so far as it arrives.
func (c *Claude) ask(ctx context.Context, system, prompt string, image []byte, mediaType string, schema map[string]any, effort string, onText func(string)) (string, error) {
	stream := c.client.Beta.Messages.NewStreaming(ctx, anthropic.BetaMessageNewParams{
		Model:     c.model,
		MaxTokens: 32000,
		// A refusal is re-served by another model in the same call.
		Betas:     []anthropic.AnthropicBeta{anthropic.AnthropicBetaServerSideFallback2026_07_01},
		Fallbacks: anthropic.BetaFallbacksParamOfDefault(),
		System:    []anthropic.BetaTextBlockParam{{Text: system}},
		OutputConfig: anthropic.BetaOutputConfigParam{
			Effort: anthropic.BetaOutputConfigEffort(effort),
			Format: anthropic.BetaJSONOutputFormatParam{Schema: schema},
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
	for stream.Next() {
		event := stream.Current()
		if err := resp.Accumulate(event); err != nil {
			return "", fmt.Errorf("read shift sheet: %w", err)
		}
		if onText != nil && event.Type == "content_block_delta" && event.Delta.Type == "text_delta" {
			sofar.WriteString(event.Delta.Text)
			onText(sofar.String())
		}
	}
	if err := stream.Err(); err != nil {
		return "", fmt.Errorf("read shift sheet: %w", err)
	}
	if resp.StopReason == anthropic.BetaStopReasonRefusal {
		return "", ErrRefused
	}
	var text strings.Builder
	for _, block := range resp.Content {
		if t, ok := block.AsAny().(anthropic.BetaTextBlock); ok {
			text.WriteString(t.Text)
		}
	}
	if resp.StopReason == anthropic.BetaStopReasonMaxTokens {
		return "", fmt.Errorf("read shift sheet: answer cut off at max tokens")
	}
	return text.String(), nil
}
