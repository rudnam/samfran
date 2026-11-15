package server_test

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/rudnam/samfran/internal/config"
)

// fakeCalendar is a tiny Google: OAuth tokens and the Calendar API calls
// samfran makes.
type fakeCalendar struct {
	mu        sync.Mutex
	calendars map[string]string                    // id -> name
	events    map[string]map[string]map[string]any // calendar -> event id -> event
	revoked   bool
	refresh   string
}

func newFakeCalendar() (*fakeCalendar, *httptest.Server) {
	f := &fakeCalendar{calendars: map[string]string{}, events: map[string]map[string]map[string]any{}}
	ts := httptest.NewServer(http.HandlerFunc(f.serve))
	return f, ts
}

func (f *fakeCalendar) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	write := func(v any) { json.NewEncoder(w).Encode(v) }
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	switch {
	case r.URL.Path == "/token":
		r.ParseForm()
		if r.Form.Get("grant_type") == "authorization_code" {
			if r.Form.Get("code") != "good-code" || r.Form.Get("code_verifier") == "" {
				w.WriteHeader(400)
				return
			}
			f.refresh = "refresh-1"
			write(map[string]any{"access_token": "access-1", "refresh_token": f.refresh, "expires_in": 3600})
			return
		}
		if f.revoked || r.Form.Get("refresh_token") != f.refresh {
			w.WriteHeader(400)
			write(map[string]string{"error": "invalid_grant"})
			return
		}
		write(map[string]any{"access_token": "access-1", "expires_in": 3600})
	case r.URL.Path == "/revoke":
		f.revoked = true
	case r.Header.Get("Authorization") != "Bearer access-1":
		w.WriteHeader(401)
	case r.Method == "POST" && r.URL.Path == "/calendars":
		var in map[string]string
		json.NewDecoder(r.Body).Decode(&in)
		id := "cal" + string(rune('0'+len(f.calendars))) + "@group.calendar.google.com"
		f.calendars[id], f.events[id] = in["summary"], map[string]map[string]any{}
		write(map[string]string{"id": id})
	case len(parts) == 2 && parts[0] == "calendars" && r.Method == "GET":
		if _, ok := f.calendars[parts[1]]; !ok {
			w.WriteHeader(404)
		}
	case len(parts) >= 3 && parts[0] == "calendars" && parts[2] == "events":
		evs, ok := f.events[parts[1]]
		if !ok {
			w.WriteHeader(404)
			return
		}
		switch {
		case r.Method == "GET":
			var items []map[string]any
			for id, e := range evs {
				if e["status"] != "cancelled" {
					items = append(items, map[string]any{"id": id, "start": e["start"]})
				}
			}
			write(map[string]any{"items": items})
		case r.Method == "PUT":
			if _, ok := evs[parts[3]]; !ok {
				w.WriteHeader(404)
				return
			}
			var e map[string]any
			json.NewDecoder(r.Body).Decode(&e)
			evs[parts[3]] = e
		case r.Method == "POST":
			var e map[string]any
			json.NewDecoder(r.Body).Decode(&e)
			evs[e["id"].(string)] = e
		case r.Method == "DELETE":
			evs[parts[3]]["status"] = "cancelled"
		}
	default:
		w.WriteHeader(404)
	}
}

// summaries is the calendar's live events: event id -> title.
func (f *fakeCalendar) summaries() map[string]string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := map[string]string{}
	for _, evs := range f.events {
		for id, e := range evs {
			if e["status"] != "cancelled" {
				out[id] = e["summary"].(string)
			}
		}
	}
	return out
}

// eventually waits for the background sync.
func eventually(t *testing.T, what string, ok func() bool) {
	t.Helper()
	for i := 0; i < 100; i++ {
		if ok() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestGoogleCalendarSync(t *testing.T) {
	fake, google := newFakeCalendar()
	defer google.Close()
	ts := newTestServer(t, func(c *config.Config) {
		c.GoogleCalendarClientID, c.GoogleCalendarClientSecret = "cid", "secret"
		c.SecretsKey = []byte("0123456789abcdef0123456789abcdef")
		c.PublicOrigin = "https://samfran.example"
		c.GoogleCalendarURLs = [4]string{google.URL + "/auth", google.URL + "/token", google.URL, google.URL + "/revoke"}
	})
	c := newClient(t, ts)
	c.headers["Origin"] = "https://samfran.example"
	c.signup("fran@example.com")
	c.do("PUT", "/api/shifts/settings", map[string]any{"person_name": "Sam"}, nil)
	month := time.Now().Format("2006-01")
	c.do("PUT", "/api/shifts/month", map[string]any{"month": month, "days": []map[string]string{
		{"date": month + "-01", "code": "13F"}, {"date": month + "-02", "code": "普"}, {"date": month + "-03", "code": "22F"}}}, nil)

	var st struct {
		Available, Connected bool
		Error                string
	}
	if c.do("GET", "/api/calendar", nil, &st); !st.Available || st.Connected {
		t.Fatalf("before connecting: %+v", st)
	}

	// Connect: start sends the browser to Google with PKCE and the narrow scope.
	c.http.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	req, _ := http.NewRequest("GET", ts.URL+"/api/calendar/google/start", nil)
	resp, err := c.http.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	to, _ := url.Parse(resp.Header.Get("Location"))
	q := to.Query()
	if resp.StatusCode != 302 || q.Get("scope") != "https://www.googleapis.com/auth/calendar.app.created" ||
		q.Get("code_challenge_method") != "S256" || q.Get("access_type") != "offline" ||
		q.Get("redirect_uri") != "https://samfran.example/api/calendar/google/callback" {
		t.Fatalf("start: %d %s", resp.StatusCode, to)
	}
	// A forged callback (wrong state) connects nothing.
	resp, _ = c.http.Get(ts.URL + "/api/calendar/google/callback?code=good-code&state=forged")
	if loc := resp.Header.Get("Location"); !strings.HasSuffix(loc, "calendar=expired") {
		t.Errorf("forged callback: %s", loc)
	}
	// Google sends the browser back with a code; the calendar fills in the background.
	resp, _ = c.http.Get(ts.URL + "/api/calendar/google/callback?code=good-code&state=" + q.Get("state"))
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if loc := resp.Header.Get("Location"); loc != "/app/#/shifts/settings?calendar=connected" {
		t.Fatalf("callback: %s", loc)
	}
	c.http.CheckRedirect = nil
	id := func(day string) string { return "sf" + strings.ReplaceAll(month, "-", "") + day }
	eventually(t, "the month's events", func() bool { return len(fake.summaries()) == 3 })
	got := fake.summaries()
	if got[id("01")] != "遅番" || got[id("02")] != "休み" || got[id("03")] != "夜勤" {
		t.Errorf("events: %v", got)
	}
	for _, name := range fake.calendars {
		if name != "Sam's shifts" {
			t.Errorf("calendar named %q", name)
		}
	}

	// Saving again updates in place and removes cleared days.
	c.do("PUT", "/api/shifts/month", map[string]any{"month": month, "days": []map[string]string{
		{"date": month + "-01", "code": "7F"}, {"date": month + "-03", "code": "22F"}}}, nil)
	eventually(t, "the update", func() bool { s := fake.summaries(); return len(s) == 2 && s[id("01")] == "早番" })
	if c.do("GET", "/api/calendar", nil, &st); !st.Connected || st.Error != "" {
		t.Errorf("after syncing: %+v", st)
	}

	// Disconnecting revokes Google's access.
	c.do("DELETE", "/api/calendar", nil, &st)
	if st.Connected || !fake.revoked {
		t.Errorf("disconnect: %+v revoked=%v", st, fake.revoked)
	}
}

func TestGoogleCalendarOffWithoutSettings(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("fran@example.com")
	var st struct{ Available bool }
	if c.do("GET", "/api/calendar", nil, &st); st.Available {
		t.Error("available without a client")
	}
	if code := c.do("POST", "/api/calendar/sync", nil, nil); code != 404 {
		t.Errorf("sync without a client: %d", code)
	}
}
