package server_test

import (
	"bytes"
	"context"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"testing"

	"github.com/rudnam/samfran/internal/config"
	"github.com/rudnam/samfran/internal/shift"
)

// fakeReader stands in for Claude: it "reads" a fixed November.
type fakeReader struct{ gotName string }

func (f *fakeReader) Read(_ context.Context, image []byte, mediaType, name string, _ shift.Month) (shift.Sheet, error) {
	f.gotName = name
	s := shift.Sheet{Found: name == "オトル サマンタ", NameAsRead: name, Year: 2026, Month: 11}
	for i, code := range []string{"普", "13F", "法", "22F"} {
		s.Days = append(s.Days, struct {
			Day  int    `json:"day"`
			Code string `json:"code"`
		}{i + 1, code})
	}
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
	}
	NameAsRead string `json:"name_as_read"`
}

// upload posts a fake JPEG as the photo.
func upload(t *testing.T, c *client, out any) int {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("photo", "sheet.jpg")
	fw.Write([]byte("\xff\xd8\xff\xe0 not really a jpeg"))
	mw.Close()
	return c.send("/api/shifts/read", mw.FormDataContentType(), body.Bytes(), nil, out)
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
	if code := upload(t, c, &m); code != 200 || m.Month != "2026-11" || len(m.Days) != 4 || reader.gotName != "オトル サマンタ" {
		t.Fatalf("read: %d %+v", code, m)
	}
	if m.Days[3].Label != "Night" || !strings.HasPrefix(m.Days[3].Start, "2026-11-04T22:00:00+09:00") || !strings.HasPrefix(m.Days[3].End, "2026-11-05T06:00:00+09:00") {
		t.Errorf("night shift: %+v", m.Days[3])
	}
	var saved monthJSON
	if c.do("GET", "/api/shifts?month=2026-11", nil, &saved); len(saved.Days) != 0 {
		t.Fatalf("reading stored days: %+v", saved.Days)
	}

	// Saving, with a fix, replaces the month.
	days := []map[string]string{{"date": "2026-11-02", "code": "13F"}, {"date": "2026-11-04", "code": "22f"}, {"date": "2026-11-05", "code": ""}}
	if code := c.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": days}, &saved); code != 200 || len(saved.Days) != 2 || saved.Days[1].Code != "22F" {
		t.Fatalf("save: %d %+v", code, saved)
	}
	if code := c.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": []map[string]string{{"date": "2026-12-01", "code": "7F"}}}, nil); code != 400 {
		t.Errorf("a day outside the month: %d, want 400", code)
	}

	// A letter's length is a setting, and applies to saved days at once.
	c.do("PUT", "/api/shifts/settings", map[string]any{"lengths": map[string]float64{"F": 9}}, nil)
	c.do("GET", "/api/shifts?month=2026-11", nil, &saved)
	if !strings.HasPrefix(saved.Days[0].End, "2026-11-02T22:00:00+09:00") {
		t.Errorf("13F with F = 9 hours: %+v", saved.Days[0])
	}

	// The calendar feed: off until turned on, then readable without a session.
	if saved.Settings.FeedPath != "" {
		t.Fatalf("feed on by default: %q", saved.Settings.FeedPath)
	}
	c.do("POST", "/api/shifts/feed", nil, &saved.Settings)
	resp, body := get(t, ts.URL+saved.Settings.FeedPath)
	if resp.StatusCode != 200 || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/calendar") ||
		!strings.Contains(body, "SUMMARY:Sam: Night (22F)") {
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
