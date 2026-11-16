// Package space is what the two of you share. Everything in the app belongs
// to a space, not to one person: each account is in exactly one, which it
// gets the first time it signs in, unless someone invited its email, in
// which case it joins theirs. Features put Require in front of their routes
// and scope every query by SpaceFrom(r).
package space

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/db"
	"github.com/rudnam/samfran/internal/httpx"
)

const maxNameLen = 40

// Member is someone in the space.
type Member struct {
	UserID int64  `json:"-"`
	Name   string `json:"name"`
	Email  string `json:"email"`
	// Me is the person asking.
	Me bool `json:"me"`
}

// Invite is an address that may join the space.
type Invite struct {
	Email     string    `json:"email"`
	CreatedAt time.Time `json:"created_at"`
}

// Info is the space as its members see it.
type Info struct {
	Members []Member `json:"members"`
	Invites []Invite `json:"invites"`
}

// ---------- Store ----------

type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

// Of returns the user's space. A user without one joins the space their
// email was invited to, if any, and otherwise gets a new one.
func (s *Store) Of(ctx context.Context, userID int64) (int64, error) {
	var id int64
	err := s.db.QueryRowContext(ctx, `SELECT space_id FROM space_members WHERE user_id = $1`, userID).Scan(&id)
	if err == nil || !errors.Is(err, sql.ErrNoRows) {
		return id, err
	}
	id, err = s.join(ctx, userID)
	if db.IsUniqueViolation(err) {
		// Another request joined at the same moment; use what it chose.
		err = s.db.QueryRowContext(ctx, `SELECT space_id FROM space_members WHERE user_id = $1`, userID).Scan(&id)
	}
	return id, err
}

func (s *Store) join(ctx context.Context, userID int64) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	var id int64
	err = tx.QueryRowContext(ctx, `
		DELETE FROM space_invites i USING users u
		WHERE u.id = $1 AND i.email = u.email
		RETURNING i.space_id`, userID).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		err = tx.QueryRowContext(ctx, `INSERT INTO spaces DEFAULT VALUES RETURNING id`).Scan(&id)
	}
	if err != nil {
		return 0, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO space_members (user_id, space_id) VALUES ($1, $2)`, userID, id); err != nil {
		return 0, err
	}
	return id, tx.Commit()
}

// Invited reports whether someone invited this (normalized) email.
func (s *Store) Invited(ctx context.Context, email string) (bool, error) {
	var ok bool
	err := s.db.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM space_invites WHERE email = $1)`, email).Scan(&ok)
	return ok, err
}

// Members returns the space's members, in the order they joined. me marks
// the person asking.
func (s *Store) Members(ctx context.Context, spaceID, me int64) ([]Member, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT u.id, u.email, m.display_name FROM space_members m JOIN users u ON u.id = m.user_id
		WHERE m.space_id = $1 ORDER BY m.joined_at, u.id`, spaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Member{}
	for rows.Next() {
		var m Member
		if err := rows.Scan(&m.UserID, &m.Email, &m.Name); err != nil {
			return nil, err
		}
		m.Name = DisplayName(m.Name, m.Email)
		m.Me = m.UserID == me
		out = append(out, m)
	}
	return out, rows.Err()
}

// DisplayName is what the app calls a member: their chosen name, else the
// part of their email before the @.
func DisplayName(name, email string) string {
	if name != "" {
		return name
	}
	local, _, _ := strings.Cut(email, "@")
	return local
}

func (s *Store) Invites(ctx context.Context, spaceID int64) ([]Invite, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT email, created_at FROM space_invites WHERE space_id = $1 ORDER BY created_at`, spaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Invite{}
	for rows.Next() {
		var i Invite
		if err := rows.Scan(&i.Email, &i.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, i)
	}
	return out, rows.Err()
}

var (
	errHasAccount = errors.New("has an account")
	errInvited    = errors.New("invited elsewhere")
)

// Invite lets email join the space. It refuses an address that already has
// an account (which already has a space of its own) or is invited to
// another space.
func (s *Store) Invite(ctx context.Context, spaceID, by int64, email string) error {
	var exists bool
	if err := s.db.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM users WHERE email = $1)`, email).Scan(&exists); err != nil {
		return err
	}
	if exists {
		return errHasAccount
	}
	res, err := s.db.ExecContext(ctx, `
		INSERT INTO space_invites (email, space_id, invited_by) VALUES ($1, $2, $3)
		ON CONFLICT (email) DO UPDATE SET created_at = now() WHERE space_invites.space_id = $2`,
		email, spaceID, by)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return errInvited
	}
	return nil
}

func (s *Store) Uninvite(ctx context.Context, spaceID int64, email string) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM space_invites WHERE email = $1 AND space_id = $2`, email, spaceID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return db.ErrNotFound
	}
	return nil
}

func (s *Store) SetName(ctx context.Context, userID int64, name string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE space_members SET display_name = $2 WHERE user_id = $1`, userID, name)
	return err
}

// ---------- HTTP ----------

type ctxKey struct{}

// Require is account.RequireUser that also finds the user's space, for
// SpaceFrom.
func (h *Handler) Require(next http.Handler) http.Handler {
	return h.requireUser(httpx.HandlerFunc(func(w http.ResponseWriter, r *http.Request) error {
		id, err := h.store.Of(r.Context(), account.UserFrom(r).ID)
		if err != nil {
			return err
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
		return nil
	}))
}

// SpaceFrom returns the signed-in user's space. Call it only behind Require.
func SpaceFrom(r *http.Request) int64 {
	id, ok := r.Context().Value(ctxKey{}).(int64)
	if !ok {
		panic("space.SpaceFrom called on a route without space.Require")
	}
	return id
}

type Handler struct {
	// Log, if set, records changes for the Us tab's history (internal/activity).
	Log         func(ctx context.Context, spaceID, userID int64, via, kind string, details map[string]any)
	store       *Store
	requireUser func(http.Handler) http.Handler
}

func NewHandler(st *Store, requireUser func(http.Handler) http.Handler) *Handler {
	return &Handler{store: st, requireUser: requireUser}
}

func (h *Handler) Register(mux *http.ServeMux) {
	mux.Handle("GET /api/space", h.Require(httpx.HandlerFunc(h.get)))
	mux.Handle("PUT /api/space/me", h.Require(httpx.HandlerFunc(h.setName)))
	mux.Handle("POST /api/space/invites", h.Require(httpx.HandlerFunc(h.invite)))
	mux.Handle("DELETE /api/space/invites/{email}", h.Require(httpx.HandlerFunc(h.uninvite)))
}

func (h *Handler) info(r *http.Request) (Info, error) {
	var in Info
	var err error
	if in.Members, err = h.store.Members(r.Context(), SpaceFrom(r), account.UserFrom(r).ID); err != nil {
		return in, err
	}
	in.Invites, err = h.store.Invites(r.Context(), SpaceFrom(r))
	return in, err
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	in, err := h.info(r)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, in)
	return nil
}

func (h *Handler) setName(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Name string `json:"name"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	name := strings.TrimSpace(in.Name)
	if utf8.RuneCountInString(name) > maxNameLen {
		return httpx.Errorf(http.StatusBadRequest, "a name must be at most %d characters", maxNameLen)
	}
	if err := h.store.SetName(r.Context(), account.UserFrom(r).ID, name); err != nil {
		return err
	}
	return h.get(w, r)
}

func (h *Handler) invite(w http.ResponseWriter, r *http.Request) error {
	if err := account.NotInDemo(r); err != nil {
		return err
	}
	var in struct {
		Email string `json:"email"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	email, ok := account.NormalizeEmail(in.Email)
	if !ok {
		return httpx.Errorf(http.StatusBadRequest, "enter a valid email address")
	}
	err := h.store.Invite(r.Context(), SpaceFrom(r), account.UserFrom(r).ID, email)
	switch {
	case errors.Is(err, errHasAccount):
		return httpx.Errorf(http.StatusConflict, "this email already has an account of its own")
	case errors.Is(err, errInvited):
		return httpx.Errorf(http.StatusConflict, "this email is already invited by someone else")
	case err != nil:
		return err
	}
	if h.Log != nil {
		h.Log(r.Context(), SpaceFrom(r), account.UserFrom(r).ID, "app", "invited", map[string]any{"email": email})
	}
	return h.get(w, r)
}

func (h *Handler) uninvite(w http.ResponseWriter, r *http.Request) error {
	email := strings.ToLower(r.PathValue("email"))
	if err := h.store.Uninvite(r.Context(), SpaceFrom(r), email); errors.Is(err, db.ErrNotFound) {
		return httpx.Errorf(http.StatusNotFound, "no such invitation")
	} else if err != nil {
		return err
	}
	if h.Log != nil {
		h.Log(r.Context(), SpaceFrom(r), account.UserFrom(r).ID, "app", "invite_cancelled", map[string]any{"email": email})
	}
	return h.get(w, r)
}
