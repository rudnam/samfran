package server_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/rudnam/samfran/internal/config"
	"github.com/rudnam/samfran/internal/shift"
)

// fakeReader stands in for Claude: it "reads" a fixed November.
type fakeReader struct{ gotName string }

func (f *fakeReader) Read(_ context.Context, image []byte, mediaType, name string, _ shift.Month) (shift.Sheet, error) {
	f.gotName = name
	s := shift.Sheet{Found: name == "オトル サマンタ", NameAsRead: name, Year: 2026, Month: 11}
	for i, code := range []string{"普", "13F", "法", "22F"} {
		s.Days = append(s.Days, shift.SheetDay{Day: i + 1, Code: code})
	}
	s.Days[1].With = []string{" タナカ  ユイ ", "スズキ ケン", "スズキ ケン", ""} // tidied: one space, no repeats or blanks
	s.Days[2].With = []string{"サトウ ミホ"}                            // a day off: nobody kept
	// Others around 13:00–22:00 on the 2nd: one there, one gone by then, one
	// night shift from the day before that's over, and one also in With.
	s.Days[1].Others = []shift.Coworker{{Name: "ヤマダ ハナ", Code: "9F"}, {Name: "コバヤシ リク", Code: "7B"},
		{Name: "ヨル タロウ", Code: "22F", DayOffset: -1}, {Name: "スズキ ケン", Code: "9F"}}
	// The night shift on the 4th runs into the 5th's early morning.
	s.Days[3].Others = []shift.Coworker{{Name: "アサ ミキ", Code: "6D", DayOffset: 1}, {Name: "ナカ", Code: "17D"}}
	// A shift with a small 有休 under it: taken as leave, so nobody's with her.
	s.Days = append(s.Days, shift.SheetDay{Day: 5, Code: "9D", Note: "有休", With: []string{"サトウ ミホ"}})
	return s, nil
}

type monthJSON struct {
	Month    string
	Settings struct {
		PersonName  string `json:"person_name"`
		NameOnSheet string `json:"name_on_sheet"`
		FeedPath    string `json:"feed_path"`
		ClosingDays []int  `json:"closing_days"`
		Reader      bool
	}
	Days []struct {
		Date, Code, Kind, Label, Problem string
		Start, End                       string
		With                             []string
		Others                           []struct {
			Name, Code string
			DayOffset  int `json:"day_offset"`
			Start, End string
		}
		People []map[string]any
	}
	ClosingDays   []int  `json:"closing_days"`
	ClosingCustom bool   `json:"closing_custom"`
	NameAsRead    string `json:"name_as_read"`
	Notes         string
}

// upload posts a fake JPEG as the photo to the reading that answers at once.
func upload(t *testing.T, c *client, out any) int {
	t.Helper()
	return uploadTo(t, c, "/api/shifts/read", out)
}

// uploadTo posts a fake JPEG as the photo to path.
func uploadTo(t *testing.T, c *client, path string, out any) int {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("photo", "sheet.jpg")
	fw.Write([]byte("\xff\xd8\xff\xe0 not really a jpeg"))
	mw.Close()
	return c.send(path, mw.FormDataContentType(), body.Bytes(), nil, out)
}

func TestShifts(t *testing.T) {
	reader := &fakeReader{}
	ts := newTestServer(t, func(c *config.Config) { c.ShiftReader = reader })
	c := newClient(t, ts)
	c.signup("fran@example.com")

	// The name on the sheet comes first.
	if code := upload(t, c, nil); code != 400 {
		t.Errorf("read without a name: %d, want 400", code)
	}
	var m monthJSON
	if code := c.do("PUT", "/api/shifts/settings", map[string]any{"person_name": "Sam", "name_on_sheet": "オトル サマンタ"}, &m.Settings); code != 200 || m.Settings.PersonName != "Sam" || !m.Settings.Reader {
		t.Fatalf("settings: %d %+v", code, m.Settings)
	}

	// Reading a photo saves nothing: it answers for the person to check.
	if code := upload(t, c, &m); code != 200 || m.Month != "2026-11" || len(m.Days) != 5 || reader.gotName != "オトル サマンタ" {
		t.Fatalf("read: %d %+v", code, m)
	}
	if m.Days[3].Label != "Night" || !strings.HasPrefix(m.Days[3].Start, "2026-11-04T22:00:00+09:00") || !strings.HasPrefix(m.Days[3].End, "2026-11-05T07:00:00+09:00") {
		t.Errorf("night shift: %+v", m.Days[3])
	}
	if w := m.Days[1].With; len(w) != 2 || w[0] != "タナカ ユイ" || w[1] != "スズキ ケン" || m.Days[2].With != nil {
		t.Errorf("who's on the same shift: %+v", m.Days)
	}
	if o := m.Days[1].Others; len(o) != 1 || o[0].Name != "ヤマダ ハナ" || !strings.HasPrefix(o[0].Start, "2026-11-02T09:00:00+09:00") {
		t.Errorf("others there on the 2nd: %+v", o)
	}
	// Those not there during the shift (the 7B, the night before) aren't read at all.
	if p := m.Days[1].People; len(p) != 3 || p[0]["name"] != "タナカ ユイ" || p[1]["name"] != "スズキ ケン" || p[2]["name"] != "ヤマダ ハナ" {
		t.Errorf("people read on the 2nd: %+v", p)
	}
	if o := m.Days[3].Others; len(o) != 2 || o[0].Name != "ナカ" || o[1].Name != "アサ ミキ" || o[1].DayOffset != 1 || !strings.HasPrefix(o[1].Start, "2026-11-05T06:00:00+09:00") {
		t.Errorf("others there on the night of the 4th: %+v", o)
	}
	if d := m.Days[4]; d.Code != "有休" || d.Kind != "leave" || d.Start != "" || d.With != nil || !strings.Contains(m.Notes, "5 (9D)") {
		t.Errorf("9D with 有休 under it: %+v, notes %q", d, m.Notes)
	}
	var saved monthJSON
	if c.do("GET", "/api/shifts?month=2026-11", nil, &saved); len(saved.Days) != 0 {
		t.Fatalf("reading stored days: %+v", saved.Days)
	}

	// An older app sends with and others: others are shown if they overlap the day's shift.
	others := []map[string]any{{"name": "ヤマダ ハナ", "code": "9F"}, {"name": "ヨル", "code": "22F", "day_offset": -1}, {"name": "ヤスミ", "code": "普"}}
	if code := c.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": []map[string]any{{"date": "2026-11-02", "code": "13F", "others": others}}}, &saved); code != 200 ||
		len(saved.Days[0].Others) != 1 || saved.Days[0].Others[0].Name != "ヤマダ ハナ" {
		t.Fatalf("save with others: %d %+v", code, saved)
	}
	if c.do("GET", "/api/shifts?month=2026-11", nil, &saved); len(saved.Days[0].Others) != 1 {
		t.Fatalf("others as stored: %+v", saved.Days)
	}
	// Changed to an early shift, the same others aren't there any more but the night before is.
	if c.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": []map[string]any{{"date": "2026-11-02", "code": "5A", "others": others}}}, &saved); len(saved.Days[0].Others) != 1 || saved.Days[0].Others[0].Name != "ヨル" {
		t.Fatalf("others for 5A: %+v", saved.Days)
	}

	// Everyone read around a day is sent (people); only those on the shift or
	// there during it are kept, and who's shown follows the code.
	people := []map[string]any{{"name": "タナカ ユイ", "code": "13F"}, {"name": "ヤマダ ハナ", "code": "9F"}, {"name": "ヨル", "code": "22F", "day_offset": -1}, {"name": "コバヤシ リク", "code": "7B"}}
	shown := func(code string, people any, kept int) (string, string) {
		t.Helper()
		var m monthJSON
		if status := c.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": []map[string]any{{"date": "2026-11-02", "code": code, "people": people}}}, &m); status != 200 {
			t.Fatalf("save %s with people: %d", code, status)
		}
		c.do("GET", "/api/shifts?month=2026-11", nil, &m)
		var others []string
		for _, o := range m.Days[0].Others {
			others = append(others, o.Name)
		}
		if len(m.Days[0].People) != kept {
			t.Errorf("%s: people kept: %+v", code, m.Days[0].People)
		}
		return strings.Join(m.Days[0].With, "|"), strings.Join(others, "|")
	}
	if with, others := shown("13F", people, 2); with != "タナカ ユイ" || others != "ヤマダ ハナ" {
		t.Errorf("13F: with %q, others %q", with, others)
	}
	// A corrected code (as while checking a read, which sends everyone): the
	// one on 13F is still there, now on another shift.
	if with, others := shown("9F", people, 3); with != "ヤマダ ハナ" || others != "コバヤシ リク|タナカ ユイ" {
		t.Errorf("9F: with %q, others %q", with, others)
	}
	// Back to 13F: those who aren't there any more are dropped.
	if with, others := shown("13F", people, 2); with != "タナカ ユイ" || others != "ヤマダ ハナ" {
		t.Errorf("13F again: with %q, others %q", with, others)
	}
	// Anyone's name can be fixed, not only those on the same shift.
	people[1]["name"] = "ヤマダ ハル"
	if _, others := shown("13F", people, 2); others != "ヤマダ ハル" {
		t.Errorf("a fixed name: others %q", others)
	}

	// Saving, with a fix, replaces the month.
	// "with" is optional; a day off keeps nobody.
	days := []map[string]any{{"date": "2026-11-02", "code": "13F"}, {"date": "2026-11-04", "code": "22f", "with": []string{"タナカ ユイ"}},
		{"date": "2026-11-05", "code": ""}, {"date": "2026-11-06", "code": "普", "with": []string{"スズキ ケン"}}}
	if code := c.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": days}, &saved); code != 200 || len(saved.Days) != 3 || saved.Days[1].Code != "22F" ||
		len(saved.Days[1].With) != 1 || saved.Days[0].With != nil || saved.Days[2].With != nil {
		t.Fatalf("save: %d %+v", code, saved)
	}
	long := []string{strings.Repeat("名", 41)}
	if code := c.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": []map[string]any{{"date": "2026-11-04", "code": "22F", "with": long}}}, nil); code != 400 {
		t.Errorf("a 41-character name: %d, want 400", code)
	}
	if code := c.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": []map[string]string{{"date": "2026-12-01", "code": "7F"}}}, nil); code != 400 {
		t.Errorf("a day outside the month: %d, want 400", code)
	}

	// A letter's length is a setting, and applies to saved days at once.
	c.do("PUT", "/api/shifts/settings", map[string]any{"lengths": map[string]float64{"F": 10}}, nil)
	c.do("GET", "/api/shifts?month=2026-11", nil, &saved)
	if !strings.HasPrefix(saved.Days[0].End, "2026-11-02T23:00:00+09:00") {
		t.Errorf("13F with F = 10 hours: %+v", saved.Days[0])
	}

	// 締め申請通知: the 15th and the last day until changed; tidied when saved.
	if got := fmt.Sprint(saved.Settings.ClosingDays); got != "[15 31]" {
		t.Errorf("closing days by default: %s", got)
	}
	var st shift.Settings
	c.do("PUT", "/api/shifts/settings", map[string]any{"closing_days": []int{25, 10, 10}}, &st)
	if got := fmt.Sprint(st.ClosingDays); got != "[10 25]" || st.Lengths["F"] != 10 {
		t.Errorf("closing days saved: %s, %+v", got, st)
	}
	if code := c.do("PUT", "/api/shifts/settings", map[string]any{"closing_days": []int{32}}, nil); code != 400 {
		t.Errorf("closing day 32: %d, want 400", code)
	}

	// The calendar feed: off until turned on, then readable without a session.
	if saved.Settings.FeedPath != "" {
		t.Fatalf("feed on by default: %q", saved.Settings.FeedPath)
	}
	c.do("POST", "/api/shifts/feed", nil, &saved.Settings)
	resp, body := get(t, ts.URL+saved.Settings.FeedPath)
	if resp.StatusCode != 200 || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/calendar") ||
		!strings.Contains(body, "SUMMARY:夜勤") || strings.Contains(body, "タナカ") {
		t.Fatalf("feed: %d %s %s", resp.StatusCode, resp.Header.Get("Content-Type"), body)
	}
	old := saved.Settings.FeedPath
	c.do("DELETE", "/api/shifts/feed", nil, nil)
	if resp, _ := get(t, ts.URL+old); resp.StatusCode != 404 {
		t.Errorf("feed after turning it off: %d", resp.StatusCode)
	}

	// One month's 締め申請通知 days, saved with its shifts; the usual days
	// make it follow the settings again.
	var month monthJSON
	c.do("GET", "/api/shifts?month=2026-11", nil, &month)
	if got := fmt.Sprint(month.ClosingDays); got != "[10 25]" || month.ClosingCustom {
		t.Errorf("November's closing days, usual: %s %v", got, month.ClosingCustom)
	}
	nov := map[string]any{"month": "2026-11", "days": []map[string]string{{"date": "2026-11-02", "code": "13F"}}, "closing_days": []int{30, 20, 20}}
	if code := c.do("PUT", "/api/shifts/month", nov, &month); code != 200 || fmt.Sprint(month.ClosingDays) != "[20 30]" || !month.ClosingCustom {
		t.Errorf("November's own closing days: %d %v %v", code, month.ClosingDays, month.ClosingCustom)
	}
	c.do("GET", "/api/shifts?month=2026-12", nil, &month)
	if fmt.Sprint(month.ClosingDays) != "[10 25]" || month.ClosingCustom {
		t.Errorf("December keeps the usual days: %v %v", month.ClosingDays, month.ClosingCustom)
	}
	delete(nov, "closing_days") // an app that doesn't send them leaves them be
	c.do("PUT", "/api/shifts/month", nov, &month)
	if fmt.Sprint(month.ClosingDays) != "[20 30]" {
		t.Errorf("saved without closing days: %v", month.ClosingDays)
	}
	nov["closing_days"] = []int{31}
	if code := c.do("PUT", "/api/shifts/month", nov, nil); code != 400 {
		t.Errorf("November 31st: %d, want 400", code)
	}
	nov["closing_days"] = []int{}
	if c.do("PUT", "/api/shifts/month", nov, &month); len(month.ClosingDays) != 0 || !month.ClosingCustom {
		t.Errorf("no closing days in November: %v %v", month.ClosingDays, month.ClosingCustom)
	}
	nov["closing_days"] = []int{25, 10}
	if c.do("PUT", "/api/shifts/month", nov, &month); month.ClosingCustom {
		t.Errorf("the usual days again: still custom")
	}
	c.do("PUT", "/api/shifts/settings", map[string]any{"closing_days": []int{15, 31}}, nil)
	c.do("GET", "/api/shifts?month=2026-11", nil, &month)
	if fmt.Sprint(month.ClosingDays) != "[15 30]" {
		t.Errorf("November follows the settings: %v", month.ClosingDays)
	}

	// Another space sees none of it.
	other := newClient(t, ts)
	other.signup("x@example.com")
	var theirs monthJSON
	if other.do("GET", "/api/shifts?month=2026-11", nil, &theirs); len(theirs.Days) != 0 {
		t.Errorf("another space sees shifts: %+v", theirs.Days)
	}
}

// slowReader is fakeReader that waits to be let go, like Claude taking a minute.
type slowReader struct {
	fakeReader
	letGo chan struct{}
}

func (s *slowReader) Read(ctx context.Context, image []byte, mediaType, name string, m shift.Month) (shift.Sheet, error) {
	if m.Progress != nil {
		m.Progress(shift.Progress{NameAsRead: name, DaysRead: 12, DaysInMonth: 30})
	}
	<-s.letGo
	return s.fakeReader.Read(ctx, image, mediaType, name, m)
}

// A read is started, then asked after until it's done, so no request has to
// last as long as Claude takes.
func TestShiftReadInBackground(t *testing.T) {
	reader := &slowReader{letGo: make(chan struct{})}
	ts := newTestServer(t, func(c *config.Config) { c.ShiftReader = reader })
	c := newClient(t, ts)
	c.signup("fran@example.com")

	// Checked before it starts: the name on the sheet comes first.
	if code := uploadTo(t, c, "/api/shifts/reads", nil); code != 400 {
		t.Errorf("start without a name: %d, want 400", code)
	}
	c.do("PUT", "/api/shifts/settings", map[string]any{"name_on_sheet": "オトル サマンタ"}, nil)

	var started struct{ ID string }
	if code := uploadTo(t, c, "/api/shifts/reads", &started); code != 202 || started.ID == "" {
		t.Fatalf("start: %d %+v", code, started)
	}
	// While it's read, it says how far it's got.
	var status struct {
		Status      string
		NameAsRead  string `json:"name_as_read"`
		DaysRead    int    `json:"days_read"`
		DaysInMonth int    `json:"days_in_month"`
	}
	for range 100 {
		if code := c.do("GET", "/api/shifts/reads/"+started.ID, nil, &status); code != 202 || status.Status != "reading" {
			t.Fatalf("while reading: %d %+v", code, status)
		}
		if status.DaysRead > 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if status.NameAsRead != "オトル サマンタ" || status.DaysRead != 12 || status.DaysInMonth != 30 {
		t.Errorf("progress: %+v", status)
	}
	// Another space can't see it.
	other := newClient(t, ts)
	other.signup("x@example.com")
	if code := other.do("GET", "/api/shifts/reads/"+started.ID, nil, nil); code != 404 {
		t.Errorf("another space asks after the read: %d, want 404", code)
	}

	close(reader.letGo)
	var m monthJSON
	code := 0
	for range 100 {
		m = monthJSON{}
		if code = c.do("GET", "/api/shifts/reads/"+started.ID, nil, &m); code != 202 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if code != 200 || m.Month != "2026-11" || len(m.Days) != 5 || m.NameAsRead != "オトル サマンタ" {
		t.Fatalf("done: %d %+v", code, m)
	}
	if code := c.do("GET", "/api/shifts/reads/nope", nil, nil); code != 404 {
		t.Errorf("an unknown read: %d, want 404", code)
	}

	// A read that fails answers with the error the old endpoint gives.
	c.do("PUT", "/api/shifts/settings", map[string]any{"name_on_sheet": "誰か"}, nil)
	uploadTo(t, c, "/api/shifts/reads", &started)
	var errBody struct{ Error string }
	for range 100 {
		errBody.Error = ""
		if code = c.do("GET", "/api/shifts/reads/"+started.ID, nil, &errBody); code != 202 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if code != 422 || !strings.Contains(errBody.Error, "couldn't find 誰か") {
		t.Errorf("not on the sheet: %d %q", code, errBody.Error)
	}
}

func TestShiftReadingNeedsAKey(t *testing.T) {
	ts := newTestServer(t) // no ShiftReader, no API key
	c := newClient(t, ts)
	c.signup("fran@example.com")
	c.do("PUT", "/api/shifts/settings", map[string]any{"name_on_sheet": "X"}, nil)
	var errBody struct{ Error string }
	if code := upload(t, c, &errBody); code != 503 || !strings.Contains(errBody.Error, "by hand") {
		t.Errorf("read without a key: %d %q", code, errBody.Error)
	}
	// Not an image.
	ts2 := newTestServer(t, func(cfg *config.Config) { cfg.ShiftReader = &fakeReader{} })
	c2 := newClient(t, ts2)
	c2.signup("a@example.com")
	c2.do("PUT", "/api/shifts/settings", map[string]any{"name_on_sheet": "X"}, nil)
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("photo", "x.txt")
	io.WriteString(fw, "plain text")
	mw.Close()
	if code := c2.send("/api/shifts/read", mw.FormDataContentType(), body.Bytes(), nil, nil); code != http.StatusBadRequest {
		t.Errorf("a text file: %d, want 400", code)
	}
}

// misreader gets two names wrong, the way a photo does, and records what it was told.
type misreader struct{ known []string }

func (m *misreader) Read(_ context.Context, _ []byte, _, name string, hint shift.Month) (shift.Sheet, error) {
	m.known = hint.Names
	s := shift.Sheet{Found: true, NameAsRead: name, Year: 2026, Month: 11}
	s.Days = []shift.SheetDay{{Day: 2, Code: "13F", With: []string{"タナカ ユエ", "スズキ ケソ", "ヤマダ タロウ"}}}
	return s, nil
}

func TestShiftNameMemory(t *testing.T) {
	reader := &misreader{}
	ts := newTestServer(t, func(c *config.Config) { c.ShiftReader = reader })
	c := newClient(t, ts)
	c.signup("fran@example.com")
	c.do("PUT", "/api/shifts/settings", map[string]any{"person_name": "Sam", "name_on_sheet": "オトル サマンタ"}, nil)

	// Nobody is known yet, so the first read is as printed.
	var m monthJSON
	if code := upload(t, c, &m); code != 200 || len(m.Days) != 1 || strings.Join(m.Days[0].With, "|") != "タナカ ユエ|スズキ ケソ|ヤマダ タロウ" || len(reader.known) != 0 {
		t.Fatalf("first read: %d %+v known %v", code, m, reader.known)
	}

	// Checking it: one name fixed by hand, one left right. Both are remembered.
	days := []map[string]any{{"date": "2026-11-02", "code": "13F", "with": []string{"田中 結", "スズキ ケン", "ヤマダ タロウ"}}}
	fixes := []map[string]string{{"from": "タナカ ユエ", "to": "田中 結"}, {"from": "スズキ ケソ", "to": "スズキ ケン"}, {"from": "same", "to": "same"}, {"from": "", "to": "x"}}
	var saveErr map[string]any
	if code := c.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": days, "corrections": fixes}, &saveErr); code != 200 {
		t.Fatalf("save: %d %v", code, saveErr)
	}

	// The next read has the right names, tells the reader who's known, and says what it changed.
	if code := upload(t, c, &m); code != 200 || strings.Join(m.Days[0].With, "|") != "田中 結|スズキ ケン|ヤマダ タロウ" {
		t.Fatalf("second read: %d %+v", code, m.Days)
	}
	if len(reader.known) != 3 || !strings.Contains(m.Notes, "タナカ ユエ → 田中 結") {
		t.Errorf("known %v, notes %q", reader.known, m.Notes)
	}

	// Another space shares none of it.
	o := newClient(t, ts)
	o.signup("x@example.com")
	o.do("PUT", "/api/shifts/settings", map[string]any{"person_name": "Al", "name_on_sheet": "オトル サマンタ"}, nil)
	if code := upload(t, o, &m); code != 200 || m.Days[0].With[0] != "タナカ ユエ" || len(reader.known) != 0 {
		t.Errorf("another space: %d %+v known %v", code, m.Days, reader.known)
	}
}

// A photo sent over a phone's slow upload takes longer to arrive than the
// server lets other requests take (cmd/server). Cut off, the app could only
// say the server couldn't be reached.
func TestShiftPhotoOnASlowUpload(t *testing.T) {
	const limit = 300 * time.Millisecond // cmd/server: 30 s
	ts := newTimedTestServer(t, limit, func(c *config.Config) { c.ShiftReader = &fakeReader{} })
	c := newClient(t, ts)
	c.signup("fran@example.com")
	c.do("PUT", "/api/shifts/settings", map[string]any{"name_on_sheet": "オトル サマンタ"}, nil)

	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("photo", "sheet.jpg")
	fw.Write([]byte("\xff\xd8\xff\xe0" + strings.Repeat("x", 4000)))
	mw.Close()

	// The photo arrives in pieces over three times the limit.
	pr, pw := io.Pipe()
	go func() {
		data := body.Bytes()
		for i := 0; i < len(data); i += 500 {
			pw.Write(data[i:min(i+500, len(data))])
			time.Sleep(3 * limit / time.Duration(len(data)/500+1))
		}
		pw.Close()
	}()
	req, _ := http.NewRequest("POST", ts.URL+"/api/shifts/reads", pr)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	res, err := c.http.Do(req)
	if err != nil {
		t.Fatalf("slow upload cut off: %v", err)
	}
	defer res.Body.Close()
	var started struct{ ID string }
	json.NewDecoder(res.Body).Decode(&started)
	if res.StatusCode != 202 || started.ID == "" {
		t.Fatalf("slow upload: %d %+v", res.StatusCode, started)
	}
}
