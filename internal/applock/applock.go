// Package applock is locked apps: each of you can lock apps on your own
// phone (the Android app covers them when they're opened), and opening one
// means asking the other person. The asker says for how long and why; the
// other answers Allow (for that long, or a time of their own) or Deny, with
// a reply if they like. Unlocking an app for good is asked the same way.
// In an emergency the app can be opened without asking, and the other
// person is told.
//
// The server keeps the locks and the requests; the phone does the covering.
// Both phones hear of a request or an answer at once through internal/push,
// and the asking phone also asks after its request while it waits.
//
// Only you can lock your own apps: the other person sees which are locked,
// and answers, but can't lock anything of yours.
package applock

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/db"
	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/space"
)

const (
	// PendingFor is how long a request waits for an answer before it expires.
	PendingFor = 15 * time.Minute
	maxMinutes = 240
	maxLocks   = 50
	maxLabel   = 80
	maxMessage = 200
	// historyLen is how many past requests the page lists.
	historyLen = 30
)

var packageName = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`)

// Lock is an app someone has locked on their phone.
type Lock struct {
	ID      int64  `json:"id"`
	Package string `json:"package"`
	Label   string `json:"label"`
	// Mine: it's on the asking person's phone; otherwise it's the other's.
	Mine bool `json:"mine"`
	// Owner is the name of whose phone it's on.
	Owner     string    `json:"owner"`
	CreatedAt time.Time `json:"created_at"`
	// OpenUntil is when an allowed opening ends; null while it's locked.
	OpenUntil *time.Time `json:"open_until"`
}

// Request is asking to open (or unlock) an app, or opening it without asking.
type Request struct {
	ID int64 `json:"id"`
	// LockID is null once the app is no longer locked.
	LockID  *int64 `json:"lock_id"`
	Package string `json:"package"`
	Label   string `json:"label"`
	// Kind is open, remove (unlock for good) or override (opened without asking).
	Kind string `json:"kind"`
	// Minutes is how long it was asked to be open for (0 for remove).
	Minutes int    `json:"minutes"`
	Message string `json:"message"`
	// Status is pending, allowed, denied, cancelled, or expired (pending
	// for longer than PendingFor).
	Status string `json:"status"`
	Reply  string `json:"reply"`
	// OpenUntil, when allowed (or overridden), is when the opening ends.
	OpenUntil  *time.Time `json:"open_until"`
	Mine       bool       `json:"mine"`
	Who        string     `json:"who"`
	CreatedAt  time.Time  `json:"created_at"`
	AnsweredAt *time.Time `json:"answered_at"`
	// ExpiresAt, while pending, is when it expires.
	ExpiresAt *time.Time `json:"expires_at,omitempty"`
}

// Page is GET /api/locks.
type Page struct {
	Locks    []Lock    `json:"locks"`
	Requests []Request `json:"requests"`
	// Partner is the other person's name; "" while they haven't joined,
	// when nothing can be locked (there's nobody to ask).
	Partner string `json:"partner"`
}

// ---------- Store ----------

type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

type member struct {
	id   int64
	name string
}

// partner is the space's other member, if they've joined.
func (s *Store) partner(ctx context.Context, spaceID, me int64) (member, bool, error) {
	var m member
	var name, email string
	err := s.db.QueryRowContext(ctx, `
		SELECT u.id, m.display_name, u.email FROM space_members m JOIN users u ON u.id = m.user_id
		WHERE m.space_id = $1 AND m.user_id <> $2 ORDER BY m.joined_at LIMIT 1`, spaceID, me).Scan(&m.id, &name, &email)
	if errors.Is(err, sql.ErrNoRows) {
		return m, false, nil
	}
	m.name = space.DisplayName(name, email)
	return m, err == nil, err
}

// name is a member's display name.
func (s *Store) name(ctx context.Context, userID int64) string {
	var name, email string
	s.db.QueryRowContext(ctx, `SELECT m.display_name, u.email FROM space_members m JOIN users u ON u.id = m.user_id WHERE u.id = $1`, userID).Scan(&name, &email)
	return space.DisplayName(name, email)
}

const lockColumns = `l.id, l.package, l.label, l.user_id, coalesce(m.display_name, ''), u.email, l.created_at,
	(SELECT max(r.open_until) FROM app_lock_requests r WHERE r.lock_id = l.id AND r.status = 'allowed' AND r.open_until > now())`

func scanLock(row interface{ Scan(...any) error }, me int64) (Lock, error) {
	var l Lock
	var owner int64
	var name, email string
	err := row.Scan(&l.ID, &l.Package, &l.Label, &owner, &name, &email, &l.CreatedAt, &l.OpenUntil)
	l.Mine = owner == me
	l.Owner = space.DisplayName(name, email)
	return l, err
}

func (s *Store) Locks(ctx context.Context, spaceID, me int64) ([]Lock, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT `+lockColumns+`
		FROM app_locks l JOIN users u ON u.id = l.user_id LEFT JOIN space_members m ON m.user_id = l.user_id
		WHERE l.space_id = $1 ORDER BY l.user_id <> $2, lower(l.label), l.id`, spaceID, me)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Lock{}
	for rows.Next() {
		l, err := scanLock(rows, me)
		if err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

// requestColumns words an old pending request as expired.
const requestColumns = `r.id, r.lock_id, r.package, r.label, r.kind, r.minutes, r.message,
	CASE WHEN r.status = 'pending' AND r.created_at < now() - $INTERVAL::interval THEN 'expired' ELSE r.status END,
	r.reply, r.open_until, r.user_id, coalesce(m.display_name, ''), coalesce(u.email, ''), r.created_at, r.answered_at`

func requestSelect(where string) string {
	return `SELECT ` + strings.Replace(requestColumns, "$INTERVAL", "'"+strconv.Itoa(int(PendingFor.Seconds()))+" seconds'", 1) + `
		FROM app_lock_requests r LEFT JOIN users u ON u.id = r.user_id LEFT JOIN space_members m ON m.user_id = r.user_id
		WHERE ` + where
}

func scanRequest(row interface{ Scan(...any) error }, me int64) (Request, error) {
	var q Request
	var who int64
	var name, email string
	err := row.Scan(&q.ID, &q.LockID, &q.Package, &q.Label, &q.Kind, &q.Minutes, &q.Message, &q.Status,
		&q.Reply, &q.OpenUntil, &who, &name, &email, &q.CreatedAt, &q.AnsweredAt)
	q.Mine = who == me
	q.Who = space.DisplayName(name, email)
	if q.Status == "pending" {
		e := q.CreatedAt.Add(PendingFor)
		q.ExpiresAt = &e
	}
	return q, err
}

func (s *Store) Requests(ctx context.Context, spaceID, me int64) ([]Request, error) {
	rows, err := s.db.QueryContext(ctx, requestSelect(`r.space_id = $1 ORDER BY r.created_at DESC, r.id DESC LIMIT $2`), spaceID, historyLen)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Request{}
	for rows.Next() {
		q, err := scanRequest(rows, me)
		if err != nil {
			return nil, err
		}
		out = append(out, q)
	}
	return out, rows.Err()
}

func (s *Store) Request(ctx context.Context, spaceID, me, id int64) (Request, error) {
	q, err := scanRequest(s.db.QueryRowContext(ctx, requestSelect(`r.space_id = $1 AND r.id = $2`), spaceID, id), me)
	if errors.Is(err, sql.ErrNoRows) {
		return q, httpx.Errorf(http.StatusNotFound, "no such request")
	}
	return q, err
}

// ---------- HTTP ----------

type Handler struct {
	store *Store
	// Notify, if set, tells a person's phones at once (internal/push).
	Notify func(ctx context.Context, userID int64, data map[string]string)
}

func NewHandler(st *Store) *Handler { return &Handler{store: st} }

func (h *Handler) Register(mux *http.ServeMux, requireSpace func(http.Handler) http.Handler) {
	route := func(pattern string, f httpx.HandlerFunc) { mux.Handle(pattern, requireSpace(f)) }
	route("GET /api/locks", h.page)
	route("POST /api/locks", h.lock)
	route("POST /api/locks/{id}/override", h.override)
	route("POST /api/locks/requests", h.ask)
	route("GET /api/locks/requests/{id}", h.getRequest)
	route("POST /api/locks/requests/{id}/answer", h.answer)
	route("DELETE /api/locks/requests/{id}", h.cancel)
}

func (h *Handler) notify(ctx context.Context, userID int64, data map[string]string) {
	if h.Notify != nil {
		h.Notify(ctx, userID, data)
	}
}

func me(r *http.Request) int64 { return account.UserFrom(r).ID }

func pathID(r *http.Request) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		return 0, httpx.Errorf(http.StatusNotFound, "not found")
	}
	return id, nil
}

func (h *Handler) load(r *http.Request) (Page, error) {
	ctx, sp, user := r.Context(), space.SpaceFrom(r), me(r)
	var p Page
	var err error
	if p.Locks, err = h.store.Locks(ctx, sp, user); err != nil {
		return p, err
	}
	if p.Requests, err = h.store.Requests(ctx, sp, user); err != nil {
		return p, err
	}
	partner, ok, err := h.store.partner(ctx, sp, user)
	if ok {
		p.Partner = partner.name
	}
	return p, err
}

func (h *Handler) page(w http.ResponseWriter, r *http.Request) error {
	p, err := h.load(r)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, p)
	return nil
}

type lockInput struct {
	Package string `json:"package"`
	Label   string `json:"label"`
}

// lock locks one of your own apps: {package, label}. It needs the other
// person to have joined, as they're the one to ask.
func (h *Handler) lock(w http.ResponseWriter, r *http.Request) error {
	var in lockInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	in.Package, in.Label = strings.TrimSpace(in.Package), strings.TrimSpace(in.Label)
	if len(in.Package) > 255 || !packageName.MatchString(in.Package) {
		return httpx.Errorf(http.StatusBadRequest, "an app's package name is needed, like com.example.app")
	}
	if in.Label == "" {
		in.Label = in.Package
	}
	if utf8.RuneCountInString(in.Label) > maxLabel {
		return httpx.Errorf(http.StatusBadRequest, "an app's name must be at most %d characters", maxLabel)
	}
	ctx, sp, user := r.Context(), space.SpaceFrom(r), me(r)
	partner, ok, err := h.store.partner(ctx, sp, user)
	if err != nil {
		return err
	}
	if !ok {
		return httpx.Errorf(http.StatusConflict, "invite your partner first: they're the one to ask")
	}
	var n int
	if err := h.store.db.QueryRowContext(ctx, `SELECT count(*) FROM app_locks WHERE user_id = $1`, user).Scan(&n); err != nil {
		return err
	}
	if n >= maxLocks {
		return httpx.Errorf(http.StatusConflict, "you can lock up to %d apps", maxLocks)
	}
	_, err = h.store.db.ExecContext(ctx, `INSERT INTO app_locks (space_id, user_id, package, label) VALUES ($1, $2, $3, $4)`, sp, user, in.Package, in.Label)
	if db.IsUniqueViolation(err) {
		return httpx.Errorf(http.StatusConflict, "that app is locked already")
	}
	if err != nil {
		return err
	}
	h.notify(ctx, partner.id, map[string]string{"type": "lock_added", "who": h.store.name(ctx, user), "label": in.Label})
	p, err := h.load(r)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusCreated, p)
	return nil
}

type askInput struct {
	LockID  int64  `json:"lock_id"`
	Kind    string `json:"kind"`
	Minutes int    `json:"minutes"`
	Message string `json:"message"`
}

func checkMinutes(m int) error {
	if m < 1 || m > maxMinutes {
		return httpx.Errorf(http.StatusBadRequest, "minutes must be from 1 to %d", maxMinutes)
	}
	return nil
}

func checkText(s string) (string, error) {
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) > maxMessage {
		return "", httpx.Errorf(http.StatusBadRequest, "a message must be at most %d characters", maxMessage)
	}
	return s, nil
}

// ownLock is one of the asking person's own locks.
func (h *Handler) ownLock(r *http.Request, id int64) (Lock, error) {
	l, err := scanLock(h.store.db.QueryRowContext(r.Context(), `
		SELECT `+lockColumns+`
		FROM app_locks l JOIN users u ON u.id = l.user_id LEFT JOIN space_members m ON m.user_id = l.user_id
		WHERE l.space_id = $1 AND l.user_id = $2 AND l.id = $3`, space.SpaceFrom(r), me(r), id), me(r))
	if errors.Is(err, sql.ErrNoRows) {
		return l, httpx.Errorf(http.StatusNotFound, "no such locked app")
	}
	return l, err
}

// ask asks the other person: {lock_id, kind: open|remove, minutes, message}.
// A request already waiting for the same app is replaced.
func (h *Handler) ask(w http.ResponseWriter, r *http.Request) error {
	var in askInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	if in.Kind == "" {
		in.Kind = "open"
	}
	switch in.Kind {
	case "open":
		if err := checkMinutes(in.Minutes); err != nil {
			return err
		}
	case "remove":
		in.Minutes = 0
	default:
		return httpx.Errorf(http.StatusBadRequest, "kind must be open or remove")
	}
	var err error
	if in.Message, err = checkText(in.Message); err != nil {
		return err
	}
	l, err := h.ownLock(r, in.LockID)
	if err != nil {
		return err
	}
	ctx, sp, user := r.Context(), space.SpaceFrom(r), me(r)
	partner, ok, err := h.store.partner(ctx, sp, user)
	if err != nil {
		return err
	}
	if !ok {
		return httpx.Errorf(http.StatusConflict, "invite your partner first: they're the one to ask")
	}
	tx, err := h.store.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var replaced []int64
	rows, err := tx.QueryContext(ctx, `UPDATE app_lock_requests SET status = 'cancelled' WHERE lock_id = $1 AND status = 'pending' RETURNING id`, l.ID)
	if err != nil {
		return err
	}
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		replaced = append(replaced, id)
	}
	rows.Close()
	var id int64
	if err := tx.QueryRowContext(ctx, `
		INSERT INTO app_lock_requests (space_id, user_id, lock_id, package, label, kind, minutes, message)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING id`,
		sp, user, l.ID, l.Package, l.Label, in.Kind, in.Minutes, in.Message).Scan(&id); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	for _, old := range replaced {
		h.notify(ctx, partner.id, map[string]string{"type": "lock_cancelled", "request_id": strconv.FormatInt(old, 10)})
	}
	q, err := h.store.Request(ctx, sp, user, id)
	if err != nil {
		return err
	}
	h.notify(ctx, partner.id, map[string]string{
		"type":       "lock_request",
		"request_id": strconv.FormatInt(q.ID, 10),
		"kind":       q.Kind,
		"who":        q.Who,
		"label":      q.Label,
		"minutes":    strconv.Itoa(q.Minutes),
		"message":    q.Message,
	})
	httpx.WriteJSON(w, http.StatusCreated, q)
	return nil
}

type overrideInput struct {
	Minutes int    `json:"minutes"`
	Message string `json:"message"`
}

// override opens one of your locked apps without asking, for {minutes}; the
// other person is told, with {message} if given.
func (h *Handler) override(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	var in overrideInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	if err := checkMinutes(in.Minutes); err != nil {
		return err
	}
	if in.Message, err = checkText(in.Message); err != nil {
		return err
	}
	l, err := h.ownLock(r, id)
	if err != nil {
		return err
	}
	ctx, sp, user := r.Context(), space.SpaceFrom(r), me(r)
	var rid int64
	if err := h.store.db.QueryRowContext(ctx, `
		INSERT INTO app_lock_requests (space_id, user_id, lock_id, package, label, kind, minutes, message, status, open_until, answered_at)
		VALUES ($1, $2, $3, $4, $5, 'override', $6, $7, 'allowed', now() + make_interval(mins => $6), now()) RETURNING id`,
		sp, user, l.ID, l.Package, l.Label, in.Minutes, in.Message).Scan(&rid); err != nil {
		return err
	}
	q, err := h.store.Request(ctx, sp, user, rid)
	if err != nil {
		return err
	}
	if partner, ok, _ := h.store.partner(ctx, sp, user); ok {
		h.notify(ctx, partner.id, map[string]string{
			"type":       "lock_override",
			"request_id": strconv.FormatInt(q.ID, 10),
			"who":        q.Who,
			"label":      q.Label,
			"minutes":    strconv.Itoa(q.Minutes),
			"message":    q.Message,
		})
	}
	httpx.WriteJSON(w, http.StatusCreated, q)
	return nil
}

func (h *Handler) getRequest(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	q, err := h.store.Request(r.Context(), space.SpaceFrom(r), me(r), id)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, q)
	return nil
}

type answerInput struct {
	Allow bool `json:"allow"`
	// Minutes, if given, is how long it's allowed for instead of what was asked.
	Minutes int    `json:"minutes"`
	Reply   string `json:"reply"`
}

// answer is the other person's Allow or Deny: {allow, minutes?, reply?}.
// Allowing a remove unlocks the app for good.
func (h *Handler) answer(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	var in answerInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	if in.Minutes != 0 {
		if err := checkMinutes(in.Minutes); err != nil {
			return err
		}
	}
	if in.Reply, err = checkText(in.Reply); err != nil {
		return err
	}
	ctx, sp, user := r.Context(), space.SpaceFrom(r), me(r)
	q, err := h.store.Request(ctx, sp, user, id)
	if err != nil {
		return err
	}
	if q.Mine {
		return httpx.Errorf(http.StatusForbidden, "you can't answer your own request")
	}
	switch q.Status {
	case "pending":
	case "expired":
		return httpx.Errorf(http.StatusConflict, "this request has expired")
	default:
		return httpx.Errorf(http.StatusConflict, "this request was already answered or cancelled")
	}
	minutes := q.Minutes
	if in.Minutes != 0 {
		minutes = in.Minutes
	}
	tx, err := h.store.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	status := "denied"
	if in.Allow {
		status = "allowed"
	}
	var requester int64
	res := tx.QueryRowContext(ctx, `
		UPDATE app_lock_requests SET status = $3, reply = $4, answered_by = $5, answered_at = now(),
			minutes = CASE WHEN $3 = 'allowed' AND kind = 'open' THEN $6 ELSE minutes END,
			open_until = CASE WHEN $3 = 'allowed' AND kind = 'open' THEN now() + make_interval(mins => $6) END
		WHERE space_id = $1 AND id = $2 AND status = 'pending' RETURNING user_id`,
		sp, id, status, in.Reply, user, minutes)
	if err := res.Scan(&requester); errors.Is(err, sql.ErrNoRows) {
		return httpx.Errorf(http.StatusConflict, "this request was already answered or cancelled")
	} else if err != nil {
		return err
	}
	if in.Allow && q.Kind == "remove" && q.LockID != nil {
		if _, err := tx.ExecContext(ctx, `DELETE FROM app_locks WHERE id = $1 AND space_id = $2`, *q.LockID, sp); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	if q, err = h.store.Request(ctx, sp, user, id); err != nil {
		return err
	}
	data := map[string]string{
		"type":       "lock_answer",
		"request_id": strconv.FormatInt(q.ID, 10),
		"kind":       q.Kind,
		"status":     q.Status,
		"who":        h.store.name(ctx, user),
		"label":      q.Label,
		"package":    q.Package,
		"minutes":    strconv.Itoa(q.Minutes),
		"reply":      q.Reply,
	}
	if q.OpenUntil != nil {
		data["open_until"] = q.OpenUntil.UTC().Format(time.RFC3339)
	}
	h.notify(ctx, requester, data)
	httpx.WriteJSON(w, http.StatusOK, q)
	return nil
}

// cancel takes back your own request while it's waiting.
func (h *Handler) cancel(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	ctx, sp, user := r.Context(), space.SpaceFrom(r), me(r)
	q, err := h.store.Request(ctx, sp, user, id)
	if err != nil {
		return err
	}
	if !q.Mine {
		return httpx.Errorf(http.StatusForbidden, "only the one who asked can take a request back")
	}
	res, err := h.store.db.ExecContext(ctx, `UPDATE app_lock_requests SET status = 'cancelled' WHERE space_id = $1 AND id = $2 AND user_id = $3 AND status = 'pending'`, sp, id, user)
	if err != nil {
		return err
	}
	if q, err = h.store.Request(ctx, sp, user, id); err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n > 0 {
		if partner, ok, _ := h.store.partner(ctx, sp, user); ok {
			h.notify(ctx, partner.id, map[string]string{"type": "lock_cancelled", "request_id": strconv.FormatInt(id, 10)})
		}
	}
	httpx.WriteJSON(w, http.StatusOK, q)
	return nil
}
