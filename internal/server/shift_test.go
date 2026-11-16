package server_test

import (
	"bytes"
	"context"
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
		Reader      bool
	}
	Days []struct {
		Date, Code, Kind, Label, Problem string
		Start, End                       string
		With                             []string
	}
	NameAsRead string `json:"name_as_read"`
	Notes      string
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
	if d := m.Days[4]; d.Code != "有休" || d.Kind != "leave" || d.Start != "" || d.With != nil || !strings.Contains(m.Notes, "5 (9D)") {
		t.Errorf("9D with 有休 under it: %+v, notes %q", d, m.Notes)
	}
	var saved monthJSON
	if c.do("GET", "/api/shifts?month=2026-11", nil, &saved); len(saved.Days) != 0 {
		t.Fatalf("reading stored days: %+v", saved.Days)
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

	// The calendar feed: off until turned on, then readable without a session.
	if saved.Settings.FeedPath != "" {
		t.Fatalf("feed on by default: %q", saved.Settings.FeedPath)
	}
	c.do("POST", "/api/shifts/feed", nil, &saved.Settings)
	resp, body := get(t, ts.URL+saved.Settings.FeedPath)
	if resp.StatusCode != 200 || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/calendar") ||
		!strings.Contains(body, "SUMMARY:夜勤") || !strings.Contains(body, "DESCRIPTION:一緒: タナカ ユイ") {
		t.Fatalf("feed: %d %s %s", resp.StatusCode, resp.Header.Get("Content-Type"), body)
	}
	old := saved.Settings.FeedPath
	c.do("DELETE", "/api/shifts/feed", nil, nil)
	if resp, _ := get(t, ts.URL+old); resp.StatusCode != 404 {
		t.Errorf("feed after turning it off: %d", resp.StatusCode)
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
	var status struct{ Status string }
	if code := c.do("GET", "/api/shifts/reads/"+started.ID, nil, &status); code != 202 || status.Status != "reading" {
		t.Fatalf("while reading: %d %+v", code, status)
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
