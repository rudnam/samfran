package server_test

import (
	"context"
	"database/sql"
	"net/http"
	"net/http/cookiejar"
	"sync"
	"testing"
	"time"

	"github.com/rudnam/samfran/internal/config"
)

// fakePush records what would have gone to each phone.
type fakePush struct {
	mu   sync.Mutex
	sent []pushed
}

type pushed struct {
	token string
	data  map[string]string
}

func (f *fakePush) Send(_ context.Context, token string, data map[string]string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent = append(f.sent, pushed{token, data})
	return nil
}

// waitFor waits for a push of the given type to the token (pushes go out in
// the background).
func (f *fakePush) waitFor(t *testing.T, token, kind string) map[string]string {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		f.mu.Lock()
		for i, p := range f.sent {
			if p.token == token && p.data["type"] == kind {
				f.sent = append(f.sent[:i], f.sent[i+1:]...)
				f.mu.Unlock()
				return p.data
			}
		}
		f.mu.Unlock()
	}
	t.Fatalf("no %s push to %s", kind, token)
	return nil
}

type lockJSON struct {
	ID        int64
	Package   string
	Label     string
	Mine      bool
	Owner     string
	OpenUntil *time.Time `json:"open_until"`
}

type lockRequestJSON struct {
	ID        int64
	LockID    *int64 `json:"lock_id"`
	Label     string
	Kind      string
	Minutes   int
	Message   string
	Status    string
	Reply     string
	OpenUntil *time.Time `json:"open_until"`
	Mine      bool
	Who       string
}

type lockPageJSON struct {
	Locks    []lockJSON
	Requests []lockRequestJSON
	Partner  string
}

func lockPair(t *testing.T) (*client, *client, *fakePush, *sql.DB) {
	t.Helper()
	fake := &fakePush{}
	ts, conn := newTestServerDB(t, closed, func(c *config.Config) { c.Push = fake; c.SignupEmails = append(c.SignupEmails, "other@example.com") })
	sam, fran := newClient(t, ts), newClient(t, ts)
	fran.signup("fran@example.com")
	fran.do("PUT", "/api/space/me", map[string]string{"name": "Fran"}, nil)
	fran.do("POST", "/api/space/invites", map[string]string{"email": "sam@example.com"}, nil)
	sam.signup("sam@example.com")
	sam.do("PUT", "/api/space/me", map[string]string{"name": "Sam"}, nil)
	for c, token := range map[*client]string{sam: "sam-phone", fran: "fran-phone"} {
		if code := c.do("PUT", "/api/push/token", map[string]string{"token": token}, nil); code != 204 {
			t.Fatalf("push token: %d", code)
		}
	}
	return sam, fran, fake, conn
}

func TestLockingNeedsSomeoneToAsk(t *testing.T) {
	ts := newTestServer(t)
	alone := newClient(t, ts)
	alone.signup("alone@example.com")
	var p lockPageJSON
	alone.do("GET", "/api/locks", nil, &p)
	if p.Partner != "" || len(p.Locks) != 0 {
		t.Errorf("page %+v", p)
	}
	if code := alone.do("POST", "/api/locks", map[string]string{"package": "com.instagram.android", "label": "Instagram"}, nil); code != 409 {
		t.Errorf("lock alone: %d, want 409", code)
	}
}

func TestAskingToOpenALockedApp(t *testing.T) {
	sam, fran, fake, _ := lockPair(t)

	var p lockPageJSON
	if code := sam.do("POST", "/api/locks", map[string]string{"package": "com.instagram.android", "label": "Instagram"}, &p); code != 201 {
		t.Fatalf("lock: %d", code)
	}
	if len(p.Locks) != 1 || !p.Locks[0].Mine || p.Locks[0].OpenUntil != nil || p.Partner != "Fran" {
		t.Fatalf("after lock: %+v", p)
	}
	fake.waitFor(t, "fran-phone", "lock_added")
	lock := p.Locks[0].ID
	for _, bad := range []map[string]string{{"package": "not a package"}, {"package": "com.instagram.android"}} {
		if code := sam.do("POST", "/api/locks", bad, nil); code != 400 && code != 409 {
			t.Errorf("lock %v: %d", bad, code)
		}
	}
	// Fran sees Sam's lock, but it isn't hers to ask about.
	fran.do("GET", "/api/locks", nil, &p)
	if len(p.Locks) != 1 || p.Locks[0].Mine || p.Locks[0].Owner != "Sam" || p.Partner != "Sam" {
		t.Fatalf("fran's view: %+v", p)
	}
	if code := fran.do("POST", "/api/locks/requests", map[string]any{"lock_id": lock, "minutes": 10}, nil); code != 404 {
		t.Errorf("asking about someone else's lock: %d, want 404", code)
	}

	// Sam asks for 15 minutes, saying why.
	if code := sam.do("POST", "/api/locks/requests", map[string]any{"lock_id": lock, "minutes": 500}, nil); code != 400 {
		t.Errorf("500 minutes: %d, want 400", code)
	}
	var q lockRequestJSON
	if code := sam.do("POST", "/api/locks/requests", map[string]any{"lock_id": lock, "minutes": 15, "message": "Mum sent a photo"}, &q); code != 201 {
		t.Fatalf("ask: %d", code)
	}
	if q.Status != "pending" || q.Minutes != 15 || q.Message != "Mum sent a photo" || !q.Mine || q.Kind != "open" {
		t.Errorf("request %+v", q)
	}
	got := fake.waitFor(t, "fran-phone", "lock_request")
	if got["who"] != "Sam" || got["label"] != "Instagram" || got["minutes"] != "15" || got["message"] != "Mum sent a photo" || got["request_id"] != itoa(q.ID) {
		t.Errorf("push to fran: %v", got)
	}
	if code := sam.do("POST", "/api/locks/requests/"+itoa(q.ID)+"/answer", map[string]any{"allow": true}, nil); code != 403 {
		t.Errorf("answering your own: %d, want 403", code)
	}

	// Fran allows it, for 10 minutes rather than 15.
	var a lockRequestJSON
	if code := fran.do("POST", "/api/locks/requests/"+itoa(q.ID)+"/answer", map[string]any{"allow": true, "minutes": 10, "reply": "ok, 10"}, &a); code != 200 {
		t.Fatalf("answer: %d", code)
	}
	if a.Status != "allowed" || a.Minutes != 10 || a.Reply != "ok, 10" || a.OpenUntil == nil || a.Mine {
		t.Errorf("answered %+v", a)
	}
	if left := time.Until(*a.OpenUntil); left < 9*time.Minute || left > 11*time.Minute {
		t.Errorf("open for %v, want 10 minutes", left)
	}
	got = fake.waitFor(t, "sam-phone", "lock_answer")
	if got["status"] != "allowed" || got["who"] != "Fran" || got["package"] != "com.instagram.android" || got["open_until"] == "" || got["reply"] != "ok, 10" {
		t.Errorf("push to sam: %v", got)
	}
	sam.do("GET", "/api/locks", nil, &p)
	if p.Locks[0].OpenUntil == nil || len(p.Requests) != 1 {
		t.Errorf("after allow: %+v", p)
	}
	if code := fran.do("POST", "/api/locks/requests/"+itoa(q.ID)+"/answer", map[string]any{"allow": false}, nil); code != 409 {
		t.Errorf("answering twice: %d, want 409", code)
	}

	// Asked again and denied: it stays as it was.
	sam.do("POST", "/api/locks/requests", map[string]any{"lock_id": lock, "minutes": 30}, &q)
	fran.do("POST", "/api/locks/requests/"+itoa(q.ID)+"/answer", map[string]any{"allow": false, "reply": "bedtime"}, &a)
	if a.Status != "denied" || a.OpenUntil != nil {
		t.Errorf("denied %+v", a)
	}
	if got := fake.waitFor(t, "sam-phone", "lock_answer"); got["status"] != "denied" || got["reply"] != "bedtime" {
		t.Errorf("deny push: %v", got)
	}

	// Asking again replaces a request still waiting; taking it back tells Fran's phone.
	var first, second lockRequestJSON
	sam.do("POST", "/api/locks/requests", map[string]any{"lock_id": lock, "minutes": 5}, &first)
	sam.do("POST", "/api/locks/requests", map[string]any{"lock_id": lock, "minutes": 6}, &second)
	sam.do("GET", "/api/locks/requests/"+itoa(first.ID), nil, &q)
	if q.Status != "cancelled" {
		t.Errorf("replaced request: %s", q.Status)
	}
	if code := fran.do("DELETE", "/api/locks/requests/"+itoa(second.ID), nil, nil); code != 403 {
		t.Errorf("fran taking back sam's request: %d, want 403", code)
	}
	sam.do("DELETE", "/api/locks/requests/"+itoa(second.ID), nil, &q)
	if q.Status != "cancelled" {
		t.Errorf("taken back: %s", q.Status)
	}
	fake.waitFor(t, "fran-phone", "lock_cancelled")
}

func TestOpeningWithoutAskingAndUnlocking(t *testing.T) {
	sam, fran, fake, conn := lockPair(t)
	var p lockPageJSON
	sam.do("POST", "/api/locks", map[string]string{"package": "com.zhiliaoapp.musically", "label": "TikTok"}, &p)
	lock := p.Locks[0].ID

	// An emergency: open now, and Fran is told.
	var q lockRequestJSON
	if code := sam.do("POST", "/api/locks/"+itoa(lock)+"/override", map[string]any{"minutes": 5, "message": "need the bus times"}, &q); code != 201 {
		t.Fatalf("override: %d", code)
	}
	if q.Kind != "override" || q.Status != "allowed" || q.OpenUntil == nil {
		t.Errorf("override %+v", q)
	}
	if got := fake.waitFor(t, "fran-phone", "lock_override"); got["who"] != "Sam" || got["label"] != "TikTok" || got["message"] != "need the bus times" {
		t.Errorf("override push: %v", got)
	}

	// A request nobody answers expires.
	sam.do("POST", "/api/locks/requests", map[string]any{"lock_id": lock, "minutes": 5}, &q)
	fake.waitFor(t, "fran-phone", "lock_request")
	conn.Exec(`UPDATE app_lock_requests SET created_at = now() - interval '16 minutes' WHERE id = $1`, q.ID)
	if code := fran.do("POST", "/api/locks/requests/"+itoa(q.ID)+"/answer", map[string]any{"allow": true}, nil); code != 409 {
		t.Errorf("answering an expired request: %d, want 409", code)
	}
	sam.do("GET", "/api/locks/requests/"+itoa(q.ID), nil, &q)
	if q.Status != "expired" {
		t.Errorf("old request: %s", q.Status)
	}

	// Unlocking for good is asked too, and allowing it removes the lock but keeps the history.
	if code := sam.do("POST", "/api/locks/requests", map[string]any{"lock_id": lock, "kind": "remove", "message": "deleted my account"}, &q); code != 201 {
		t.Fatalf("ask to remove: %d", code)
	}
	if got := fake.waitFor(t, "fran-phone", "lock_request"); got["kind"] != "remove" {
		t.Errorf("remove push: %v", got)
	}
	fran.do("POST", "/api/locks/requests/"+itoa(q.ID)+"/answer", map[string]any{"allow": true}, nil)
	sam.do("GET", "/api/locks", nil, &p)
	if len(p.Locks) != 0 || len(p.Requests) != 3 || p.Requests[0].Label != "TikTok" || p.Requests[0].LockID != nil {
		t.Errorf("after unlocking: %+v", p)
	}

	// Another space sees none of it.
	jar, _ := cookiejar.New(nil)
	other := &client{t: t, base: sam.base, http: &http.Client{Jar: jar}, headers: map[string]string{}}
	other.signup("other@example.com")
	if code := other.do("GET", "/api/locks/requests/"+itoa(q.ID), nil, nil); code != 404 {
		t.Errorf("another space's request: %d, want 404", code)
	}
}

func TestPushTokenMovesWithTheSignIn(t *testing.T) {
	sam, fran, fake, conn := lockPair(t)
	// Fran's phone is signed in as Sam now: its pushes go to Sam.
	fran.do("PUT", "/api/push/token", map[string]string{"token": "sam-phone"}, nil)
	var owner string
	conn.QueryRow(`SELECT u.email FROM push_tokens p JOIN users u ON u.id = p.user_id WHERE token = 'sam-phone'`).Scan(&owner)
	if owner != "fran@example.com" {
		t.Errorf("token's owner %q", owner)
	}
	if code := fran.do("DELETE", "/api/push/token", map[string]string{"token": "sam-phone"}, nil); code != 204 {
		t.Errorf("forget: %d", code)
	}
	if code := sam.do("PUT", "/api/push/token", map[string]string{"token": ""}, nil); code != 400 {
		t.Errorf("empty token: %d", code)
	}
	_ = fake
}

func TestCheckInsTellThePartner(t *testing.T) {
	sam, fran, fake, conn := lockPair(t)
	sam.do("POST", "/api/locks", map[string]string{"package": "com.instagram.android", "label": "Instagram"}, nil)
	fake.waitFor(t, "fran-phone", "lock_added")
	checkIn := func(c *client, body map[string]any) {
		t.Helper()
		if code := c.do("PUT", "/api/locks/check-in", body, nil); code != 204 {
			t.Fatalf("check-in: %d", code)
		}
	}
	since := time.Now().Add(-time.Hour).UTC().Format(time.RFC3339)
	covering := map[string]any{"usage_access": true, "overlay": true, "watching": true, "watching_since": since}

	// The first check-in says nothing; Fran sees Sam's phone covering.
	checkIn(sam, covering)
	checkIn(sam, covering)
	var p struct {
		Phones []struct {
			Mine     bool
			Owner    string
			Covering bool
			Quiet    bool
		}
	}
	fran.do("GET", "/api/locks", nil, &p)
	if len(p.Phones) != 1 || p.Phones[0].Mine || p.Phones[0].Owner != "Sam" || !p.Phones[0].Covering || p.Phones[0].Quiet {
		t.Fatalf("phones %+v", p.Phones)
	}

	// Usage access turned off: Fran hears, and again when it's back.
	checkIn(sam, map[string]any{"usage_access": false, "overlay": true, "watching": true, "watching_since": since})
	if got := fake.waitFor(t, "fran-phone", "lock_check"); got["state"] != "off" || got["reason"] != "usage_access" || got["who"] != "Sam" {
		t.Errorf("off: %v", got)
	}
	checkIn(sam, covering)
	if got := fake.waitFor(t, "fran-phone", "lock_check"); got["state"] != "on" {
		t.Errorf("on: %v", got)
	}

	// The watcher started after the last check-in: it was stopped in between.
	conn.Exec(`UPDATE lock_checkins SET checked_at = now() - interval '40 minutes'`)
	restarted := time.Now().Add(-10 * time.Minute).UTC().Format(time.RFC3339)
	checkIn(sam, map[string]any{"usage_access": true, "overlay": true, "watching": true, "watching_since": restarted,
		"booted_at": time.Now().Add(-48 * time.Hour).UTC().Format(time.RFC3339)})
	if got := fake.waitFor(t, "fran-phone", "lock_check"); got["state"] != "restarted" || got["to"] == "" {
		t.Errorf("restarted: %v", got)
	}
	// ... but not when the phone itself had just started.
	conn.Exec(`UPDATE lock_checkins SET checked_at = now() - interval '40 minutes'`)
	checkIn(sam, map[string]any{"usage_access": true, "overlay": true, "watching": true,
		"watching_since": time.Now().Add(-9 * time.Minute).UTC().Format(time.RFC3339),
		"booted_at":      time.Now().Add(-10 * time.Minute).UTC().Format(time.RFC3339)})

	// A day without a check-in: Fran hears once, when her own phone checks in.
	conn.Exec(`UPDATE lock_checkins SET checked_at = now() - interval '25 hours'`)
	checkIn(fran, map[string]any{"usage_access": true, "overlay": true, "watching": false})
	if got := fake.waitFor(t, "fran-phone", "lock_check"); got["state"] != "quiet" || got["since"] == "" {
		t.Errorf("quiet: %v", got)
	}
	checkIn(fran, map[string]any{"usage_access": true, "overlay": true, "watching": false})

	// Signing out says so.
	checkIn(sam, covering)
	if got := fake.waitFor(t, "fran-phone", "lock_check"); got["state"] != "on" {
		t.Errorf("back after quiet: %v", got)
	}
	checkIn(sam, map[string]any{"usage_access": true, "overlay": true, "watching": false, "reason": "signed_out"})
	if got := fake.waitFor(t, "fran-phone", "lock_check"); got["state"] != "off" || got["reason"] != "signed_out" {
		t.Errorf("signed out: %v", got)
	}

	// Nothing more was said than the above (no alert after a restart of the phone, nor a second quiet).
	time.Sleep(200 * time.Millisecond)
	fake.mu.Lock()
	defer fake.mu.Unlock()
	for _, p := range fake.sent {
		if p.data["type"] == "lock_check" {
			t.Errorf("unexpected %v", p.data)
		}
	}
}
