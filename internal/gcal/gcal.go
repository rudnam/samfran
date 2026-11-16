// Package gcal keeps a Google Calendar of the shifts in each connected
// person's own Google account. Connecting (Shifts › Settings) signs in with
// Google for the calendar.app.created scope: SamFran may only make its own
// calendars and touch events on them, never anyone's other calendars. It
// makes one calendar ("Sam's shifts"), and every time a month of shifts is
// saved, from the app or from Claude, writes that month's days to it as
// events titled 早番, 遅番, 夜勤, 休み… Changes appear within seconds, unlike
// the subscribed feed (ics.go), which Google refreshes about once a day.
//
// Each day is one event with an ID made from its date, so writing a month
// again updates events in place, and days that were cleared are deleted.
package gcal

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/shift"
	"github.com/rudnam/samfran/internal/space"
)

// Config turns the sync on. Without ClientID, SecretKey and PublicOrigin it's off.
type Config struct {
	ClientID, ClientSecret string
	// PublicOrigin gives the callback address,
	// PublicOrigin + /api/calendar/google/callback.
	PublicOrigin string
	// SecretKey seals refresh tokens at rest (32 bytes).
	SecretKey     []byte
	SecureCookies bool
	// AuthURL, TokenURL, APIBase and RevokeURL are Google's; tests use a fake.
	AuthURL, TokenURL, APIBase, RevokeURL string
}

const scope = "https://www.googleapis.com/auth/calendar.app.created"

// Syncer writes shifts to the connected people's calendars.
type Syncer struct {
	cfg    Config
	db     *sql.DB
	shifts *shift.Store
	http   *http.Client
	aead   cipher.AEAD
	// tokens caches access tokens by user, until they expire.
	mu     sync.Mutex
	tokens map[int64]cachedToken
	// busy keeps one sync per space at a time, so writes don't interleave.
	busy sync.Map
	now  func() time.Time
}

type cachedToken struct {
	token   string
	expires time.Time
}

// New returns nil when the sync isn't configured.
func New(cfg Config, conn *sql.DB, shifts *shift.Store) *Syncer {
	if cfg.ClientID == "" || len(cfg.SecretKey) != 32 || cfg.PublicOrigin == "" {
		return nil
	}
	block, err := aes.NewCipher(cfg.SecretKey)
	if err != nil {
		return nil
	}
	aead, _ := cipher.NewGCM(block)
	for _, p := range []struct {
		v   *string
		def string
	}{
		{&cfg.AuthURL, "https://accounts.google.com/o/oauth2/v2/auth"},
		{&cfg.TokenURL, "https://oauth2.googleapis.com/token"},
		{&cfg.APIBase, "https://www.googleapis.com/calendar/v3"},
		{&cfg.RevokeURL, "https://oauth2.googleapis.com/revoke"},
	} {
		if *p.v == "" {
			*p.v = p.def
		}
	}
	return &Syncer{cfg: cfg, db: conn, shifts: shifts, http: &http.Client{Timeout: 20 * time.Second}, aead: aead,
		tokens: map[int64]cachedToken{}, now: time.Now}
}

func (s *Syncer) redirectURL() string { return s.cfg.PublicOrigin + "/api/calendar/google/callback" }

// ---------- Sealing refresh tokens ----------

// seal encrypts a refresh token, bound to its user.
func (s *Syncer) seal(userID int64, token string) []byte {
	nonce := make([]byte, s.aead.NonceSize())
	rand.Read(nonce)
	return s.aead.Seal(nonce, nonce, []byte(token), []byte(fmt.Sprint(userID)))
}

func (s *Syncer) open(userID int64, sealed []byte) (string, error) {
	n := s.aead.NonceSize()
	if len(sealed) < n {
		return "", errors.New("sealed token too short")
	}
	plain, err := s.aead.Open(nil, sealed[:n], sealed[n:], []byte(fmt.Sprint(userID)))
	return string(plain), err
}

// ---------- Talking to Google ----------

// googleError is an error answer from Google.
type googleError struct {
	Status int
	Body   string
}

func (e *googleError) Error() string { return fmt.Sprintf("google: %d %s", e.Status, e.Body) }

func statusOf(err error) int {
	var ge *googleError
	if errors.As(err, &ge) {
		return ge.Status
	}
	return 0
}

func (s *Syncer) do(ctx context.Context, token, method, path string, body, out any) error {
	var r io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		r = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, s.cfg.APIBase+path, r)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := s.http.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if res.StatusCode/100 != 2 {
		return &googleError{res.StatusCode, strings.TrimSpace(string(data))}
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}

// tokenRequest posts to Google's token endpoint.
func (s *Syncer) tokenRequest(ctx context.Context, form url.Values) (access, refresh string, expires time.Duration, err error) {
	form.Set("client_id", s.cfg.ClientID)
	form.Set("client_secret", s.cfg.ClientSecret)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.cfg.TokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := s.http.Do(req)
	if err != nil {
		return
	}
	defer res.Body.Close()
	var out struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int    `json:"expires_in"`
	}
	data, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if res.StatusCode != 200 {
		err = &googleError{res.StatusCode, strings.TrimSpace(string(data))}
		return
	}
	if err = json.Unmarshal(data, &out); err != nil {
		return
	}
	return out.AccessToken, out.RefreshToken, time.Duration(out.ExpiresIn) * time.Second, nil
}

// errReconnect means Google no longer accepts the person's connection
// (they removed SamFran's access): they need to connect again.
var errReconnect = errors.New("connect Google Calendar again")

func (s *Syncer) accessToken(ctx context.Context, l link) (string, error) {
	s.mu.Lock()
	t, ok := s.tokens[l.userID]
	s.mu.Unlock()
	if ok && s.now().Before(t.expires) {
		return t.token, nil
	}
	refresh, err := s.open(l.userID, l.refreshSealed)
	if err != nil {
		return "", err
	}
	access, _, ttl, err := s.tokenRequest(ctx, url.Values{"grant_type": {"refresh_token"}, "refresh_token": {refresh}})
	if st := statusOf(err); st == 400 || st == 401 {
		return "", errReconnect
	}
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	s.tokens[l.userID] = cachedToken{access, s.now().Add(ttl - time.Minute)}
	s.mu.Unlock()
	return access, nil
}

// ---------- Links ----------

type link struct {
	userID, spaceID int64
	refreshSealed   []byte
	calendarID      string
}

func (s *Syncer) links(ctx context.Context, where string, arg int64) ([]link, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT user_id, space_id, refresh_sealed, calendar_id FROM calendar_links WHERE `+where, arg)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []link
	for rows.Next() {
		var l link
		if err := rows.Scan(&l.userID, &l.spaceID, &l.refreshSealed, &l.calendarID); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

func (s *Syncer) setResult(ctx context.Context, userID int64, err error) {
	msg := ""
	switch {
	case errors.Is(err, errReconnect):
		msg = "Google no longer allows SamFran to update the calendar; connect it again."
	case err != nil:
		msg = "The last update to Google Calendar failed; it will try again with the next change."
	}
	if err == nil {
		s.db.ExecContext(ctx, `UPDATE calendar_links SET synced_at = now(), last_error = '' WHERE user_id = $1`, userID)
	} else {
		s.db.ExecContext(ctx, `UPDATE calendar_links SET last_error = $2 WHERE user_id = $1`, userID, msg)
	}
}

// ---------- Syncing ----------

// event is the Google Calendar event for one day.
type event struct {
	ID           string     `json:"id,omitempty"`
	Summary      string     `json:"summary"`
	Description  string     `json:"description,omitempty"`
	Start        eventTime  `json:"start"`
	End          eventTime  `json:"end"`
	Transparency string     `json:"transparency,omitempty"`
	Status       string     `json:"status,omitempty"`
	Reminders    *reminders `json:"reminders,omitempty"`
}

type eventTime struct {
	Date     string `json:"date,omitempty"`
	DateTime string `json:"dateTime,omitempty"`
	TimeZone string `json:"timeZone,omitempty"`
}

type reminders struct {
	UseDefault bool  `json:"useDefault"`
	Overrides  []any `json:"overrides"`
}

// eventID is a day's event ID: Google allows a-v and 0-9, 5 to 1024 long.
func eventID(date string) string { return "sf" + strings.ReplaceAll(date, "-", "") }

func toEvent(d shift.Day, zone string) event {
	e := event{ID: eventID(d.Date), Summary: shift.Japanese(d.Label), Description: d.Code + " (SamFran)", Status: "confirmed",
		Reminders: &reminders{UseDefault: false, Overrides: []any{}}}
	if d.Start != nil && d.End != nil {
		e.Start = eventTime{DateTime: d.Start.Format(time.RFC3339), TimeZone: zone}
		e.End = eventTime{DateTime: d.End.Format(time.RFC3339), TimeZone: zone}
		return e
	}
	next, _ := time.Parse(time.DateOnly, d.Date)
	e.Start = eventTime{Date: d.Date}
	e.End = eventTime{Date: next.AddDate(0, 0, 1).Format(time.DateOnly)}
	e.Transparency = "transparent" // a day off doesn't make anyone busy
	return e
}

// ensureCalendar makes the person's calendar if it doesn't exist (yet, or
// any more: they may have deleted it).
func (s *Syncer) ensureCalendar(ctx context.Context, token string, l *link, name, zone string) error {
	if l.calendarID != "" {
		err := s.do(ctx, token, "GET", "/calendars/"+url.PathEscape(l.calendarID), nil, nil)
		if err == nil {
			return nil
		}
		if st := statusOf(err); st != 404 && st != 410 {
			return err
		}
	}
	var cal struct{ ID string }
	if err := s.do(ctx, token, "POST", "/calendars", map[string]string{"summary": name, "timeZone": zone}, &cal); err != nil {
		return err
	}
	l.calendarID = cal.ID
	_, err := s.db.ExecContext(ctx, `UPDATE calendar_links SET calendar_id = $2 WHERE user_id = $1`, l.userID, cal.ID)
	return err
}

// syncMonth makes one person's calendar match a month of shifts.
func (s *Syncer) syncMonth(ctx context.Context, token string, l link, view shift.MonthView) error {
	zone := view.Settings.TimeZone
	loc, err := time.LoadLocation(zone)
	if err != nil {
		loc, zone = time.UTC, "UTC"
	}
	cal := "/calendars/" + url.PathEscape(l.calendarID) + "/events"
	keep := map[string]bool{}
	for _, d := range view.Days {
		e := toEvent(d, zone)
		keep[e.ID] = true
		// Update in place (this also brings back a deleted event); a day
		// never written before is inserted.
		err := s.do(ctx, token, "PUT", cal+"/"+e.ID, e, nil)
		if st := statusOf(err); st == 404 {
			err = s.do(ctx, token, "POST", cal, e, nil)
		}
		if err != nil {
			return fmt.Errorf("write %s: %w", d.Date, err)
		}
	}
	// Delete the month's days that aren't shifts any more.
	first, _ := time.ParseInLocation("2006-01", view.Month, loc)
	q := url.Values{"timeMin": {first.AddDate(0, 0, -1).Format(time.RFC3339)}, "timeMax": {first.AddDate(0, 1, 1).Format(time.RFC3339)},
		"maxResults": {"250"}, "singleEvents": {"true"}}
	var list struct {
		Items []struct {
			ID    string
			Start eventTime
		}
	}
	if err := s.do(ctx, token, "GET", cal+"?"+q.Encode(), nil, &list); err != nil {
		return err
	}
	prefix := eventID(view.Month + "-")
	for _, it := range list.Items {
		if strings.HasPrefix(it.ID, prefix) && !keep[it.ID] {
			if err := s.do(ctx, token, "DELETE", cal+"/"+it.ID, nil, nil); err != nil && statusOf(err) != 404 && statusOf(err) != 410 {
				return err
			}
		}
	}
	return nil
}

// Months writes the months to every connected calendar in the space. It's
// what runs after shifts are saved; errors are kept for the person to see.
func (s *Syncer) Months(ctx context.Context, spaceID int64, months []string) {
	if s == nil {
		return
	}
	lock, _ := s.busy.LoadOrStore(spaceID, &sync.Mutex{})
	lock.(*sync.Mutex).Lock()
	defer lock.(*sync.Mutex).Unlock()
	links, err := s.links(ctx, "space_id = $1", spaceID)
	if err != nil {
		slog.ErrorContext(ctx, "calendar links", "space", spaceID, "err", err)
		return
	}
	for _, l := range links {
		err := s.syncLink(ctx, l, months)
		if err != nil {
			slog.WarnContext(ctx, "calendar sync", "user", l.userID, "err", err)
		}
		s.setResult(ctx, l.userID, err)
	}
}

func (s *Syncer) syncLink(ctx context.Context, l link, months []string) error {
	token, err := s.accessToken(ctx, l)
	if err != nil {
		return err
	}
	st, _, err := s.shifts.Settings(ctx, l.spaceID)
	if err != nil {
		return err
	}
	name := "シフト"
	if st.PersonName != "" {
		name = st.PersonName + "'s shifts"
	}
	if err := s.ensureCalendar(ctx, token, &l, name, st.TimeZone); err != nil {
		return err
	}
	for _, m := range months {
		view, err := s.shifts.Month(ctx, l.spaceID, m, s.now())
		if err != nil {
			return err
		}
		if err := s.syncMonth(ctx, token, l, view); err != nil {
			return err
		}
	}
	return nil
}

// span is the months a full sync covers: two back, twelve ahead.
func (s *Syncer) span(zone *time.Location) []string {
	now := s.now().In(zone)
	first := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, zone)
	var out []string
	for i := -2; i <= 12; i++ {
		out = append(out, first.AddDate(0, i, 0).Format("2006-01"))
	}
	return out
}

// All writes every month in the span, e.g. after connecting or after the
// shift lengths change.
func (s *Syncer) All(ctx context.Context, spaceID int64) {
	if s == nil {
		return
	}
	_, zone, err := s.shifts.Settings(ctx, spaceID)
	if err != nil {
		return
	}
	s.Months(ctx, spaceID, s.span(zone))
}

// Later runs fn in the background, after the request that asked for it
// has been answered.
func Later(fn func(ctx context.Context)) {
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()
		fn(ctx)
	}()
}

// ---------- HTTP ----------

type Handler struct {
	s       *Syncer
	require func(http.Handler) http.Handler
}

// NewHandler serves the calendar settings; s may be nil (not configured).
func NewHandler(s *Syncer, requireSpace func(http.Handler) http.Handler) *Handler {
	return &Handler{s: s, require: requireSpace}
}

func (h *Handler) Register(mux *http.ServeMux) {
	mux.Handle("GET /api/calendar", h.require(httpx.HandlerFunc(h.status)))
	mux.Handle("GET /api/calendar/google/start", h.require(httpx.HandlerFunc(h.start)))
	mux.Handle("GET /api/calendar/google/callback", h.require(httpx.HandlerFunc(h.callback)))
	mux.Handle("POST /api/calendar/sync", h.require(httpx.HandlerFunc(h.syncNow)))
	mux.Handle("DELETE /api/calendar", h.require(httpx.HandlerFunc(h.disconnect)))
}

// Status is a person's connection, as the app shows it.
type Status struct {
	// Available: the server can sync with Google Calendar.
	Available bool       `json:"available"`
	Connected bool       `json:"connected"`
	SyncedAt  *time.Time `json:"synced_at"`
	Error     string     `json:"error"`
}

func (h *Handler) status(w http.ResponseWriter, r *http.Request) error {
	st := Status{Available: h.s != nil}
	if h.s != nil {
		err := h.s.db.QueryRowContext(r.Context(), `SELECT true, synced_at, last_error FROM calendar_links WHERE user_id = $1`,
			account.UserFrom(r).ID).Scan(&st.Connected, &st.SyncedAt, &st.Error)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
	}
	httpx.WriteJSON(w, http.StatusOK, st)
	return nil
}

const stateCookie = "gcal_state"

func random() string {
	b := make([]byte, 24)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// start sends the browser to Google to allow the calendar.
func (h *Handler) start(w http.ResponseWriter, r *http.Request) error {
	if h.s == nil {
		return httpx.Errorf(http.StatusNotFound, "Google Calendar isn't set up on this server")
	}
	if err := account.NotInDemo(r); err != nil {
		return err
	}
	state, verifier := random(), random()
	http.SetCookie(w, &http.Cookie{Name: stateCookie, Value: state + "." + verifier, Path: "/api/calendar/google",
		MaxAge: 600, HttpOnly: true, Secure: h.s.cfg.SecureCookies, SameSite: http.SameSiteLaxMode})
	challenge := sha256.Sum256([]byte(verifier))
	q := url.Values{
		"client_id": {h.s.cfg.ClientID}, "redirect_uri": {h.s.redirectURL()}, "response_type": {"code"},
		"scope": {scope}, "state": {state}, "access_type": {"offline"}, "prompt": {"consent"},
		"include_granted_scopes": {"false"},
		"code_challenge":         {base64.RawURLEncoding.EncodeToString(challenge[:])}, "code_challenge_method": {"S256"},
	}
	http.Redirect(w, r, h.s.cfg.AuthURL+"?"+q.Encode(), http.StatusFound)
	return nil
}

// callback finishes connecting, then fills the calendar in the background,
// and sends the browser back to Shift settings.
func (h *Handler) callback(w http.ResponseWriter, r *http.Request) error {
	back := func(result string) error {
		http.Redirect(w, r, "/app/#/shifts/settings?calendar="+result, http.StatusFound)
		return nil
	}
	if h.s == nil {
		return back("failed")
	}
	c, err := r.Cookie(stateCookie)
	state, verifier, _ := strings.Cut(func() string {
		if err != nil {
			return ""
		}
		return c.Value
	}(), ".")
	q := r.URL.Query()
	switch {
	case q.Get("error") != "":
		return back("cancelled")
	case state == "" || q.Get("state") != state:
		// Not ours (or too late): leave a sign-in in progress alone.
		return back("expired")
	}
	// The state is used up either way.
	http.SetCookie(w, &http.Cookie{Name: stateCookie, Value: "", Path: "/api/calendar/google", MaxAge: -1})
	_, refresh, _, err := h.s.tokenRequest(r.Context(), url.Values{"grant_type": {"authorization_code"}, "code": {q.Get("code")},
		"redirect_uri": {h.s.redirectURL()}, "code_verifier": {verifier}})
	if err != nil || refresh == "" {
		slog.WarnContext(r.Context(), "calendar connect", "err", err)
		return back("failed")
	}
	userID, spaceID := account.UserFrom(r).ID, space.SpaceFrom(r)
	if _, err := h.s.db.ExecContext(r.Context(), `
		INSERT INTO calendar_links (user_id, space_id, refresh_sealed) VALUES ($1, $2, $3)
		ON CONFLICT (user_id) DO UPDATE SET space_id = $2, refresh_sealed = $3, last_error = ''`,
		userID, spaceID, h.s.seal(userID, refresh)); err != nil {
		return err
	}
	h.s.mu.Lock()
	delete(h.s.tokens, userID)
	h.s.mu.Unlock()
	Later(func(ctx context.Context) { h.s.All(ctx, spaceID) })
	return back("connected")
}

func (h *Handler) syncNow(w http.ResponseWriter, r *http.Request) error {
	if h.s == nil {
		return httpx.Errorf(http.StatusNotFound, "Google Calendar isn't set up on this server")
	}
	h.s.All(r.Context(), space.SpaceFrom(r))
	return h.status(w, r)
}

// disconnect forgets the connection and asks Google to revoke it. The
// calendar stays in the person's Google account for them to keep or delete.
func (h *Handler) disconnect(w http.ResponseWriter, r *http.Request) error {
	if h.s == nil {
		return h.status(w, r)
	}
	userID := account.UserFrom(r).ID
	links, err := h.s.links(r.Context(), "user_id = $1", userID)
	if err != nil {
		return err
	}
	for _, l := range links {
		if refresh, err := h.s.open(userID, l.refreshSealed); err == nil {
			req, _ := http.NewRequestWithContext(r.Context(), http.MethodPost, h.s.cfg.RevokeURL,
				strings.NewReader(url.Values{"token": {refresh}}.Encode()))
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			if res, err := h.s.http.Do(req); err == nil {
				res.Body.Close()
			}
		}
	}
	if _, err := h.s.db.ExecContext(r.Context(), `DELETE FROM calendar_links WHERE user_id = $1`, userID); err != nil {
		return err
	}
	h.s.mu.Lock()
	delete(h.s.tokens, userID)
	h.s.mu.Unlock()
	return h.status(w, r)
}
