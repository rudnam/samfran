// Package activity keeps a log of changes to what the two of you share
// (shifts and their settings, the calendar link, restaurants, invitations):
// who did it, whether from the app or through Claude, and when. The Us tab
// shows the latest. Features call Log after a change succeeds; a failure to
// log is logged and never fails the change.
package activity

import (
	"context"
	"database/sql"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/space"
)

// Via says where a change came from.
const (
	App    = "app"
	Claude = "claude"
)

// Entry is one change, for the app to word.
type Entry struct {
	ID      int64          `json:"id"`
	At      time.Time      `json:"at"`
	Who     string         `json:"who"` // the member's name; "" if they've left
	Me      bool           `json:"me"`
	Via     string         `json:"via"`
	Kind    string         `json:"kind"`
	Details map[string]any `json:"details"`
}

type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

// Log records a change. A nil Store logs nothing (tests, or a feature used
// without it).
func (s *Store) Log(ctx context.Context, spaceID, userID int64, via, kind string, details map[string]any) {
	if s == nil {
		return
	}
	if details == nil {
		details = map[string]any{}
	}
	b, _ := json.Marshal(details)
	if _, err := s.db.ExecContext(context.WithoutCancel(ctx),
		`INSERT INTO activity (space_id, user_id, via, kind, details) VALUES ($1, $2, $3, $4, $5)`,
		spaceID, userID, via, kind, b); err != nil {
		slog.WarnContext(ctx, "log activity", "kind", kind, "err", err)
	}
}

// BusStarted is a bus alarm starting: logged so the other person's phone
// can be told, but not one of the changes the Us tab lists.
const BusStarted = "bus_started"

// List returns the space's latest changes, newest first; with bus, bus
// alarms started too.
func (s *Store) List(ctx context.Context, spaceID, me int64, limit int, bus bool) ([]Entry, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT a.id, a.created_at, coalesce(m.display_name, ''), coalesce(u.email, ''), coalesce(a.user_id, 0), a.via, a.kind, a.details
		FROM activity a LEFT JOIN users u ON u.id = a.user_id LEFT JOIN space_members m ON m.user_id = a.user_id
		WHERE a.space_id = $1 AND ($3 OR a.kind <> $4) ORDER BY a.created_at DESC, a.id DESC LIMIT $2`, spaceID, limit, bus, BusStarted)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Entry{}
	for rows.Next() {
		var e Entry
		var name, email string
		var uid int64
		var details []byte
		if err := rows.Scan(&e.ID, &e.At, &name, &email, &uid, &e.Via, &e.Kind, &details); err != nil {
			return nil, err
		}
		if email != "" {
			e.Who = space.DisplayName(name, email)
		}
		e.Me = uid == me
		json.Unmarshal(details, &e.Details)
		out = append(out, e)
	}
	return out, rows.Err()
}

// Handler serves GET /api/activity: the latest 50 changes. With ?bus=1
// (the phone's notification check), bus alarms started are among them.
type Handler struct{ store *Store }

func NewHandler(st *Store) *Handler { return &Handler{store: st} }

func (h *Handler) Register(mux *http.ServeMux, requireSpace func(http.Handler) http.Handler, userOf func(*http.Request) int64) {
	mux.Handle("GET /api/activity", requireSpace(httpx.HandlerFunc(func(w http.ResponseWriter, r *http.Request) error {
		list, err := h.store.List(r.Context(), space.SpaceFrom(r), userOf(r), 50, r.URL.Query().Get("bus") == "1")
		if err != nil {
			return err
		}
		httpx.WriteJSON(w, http.StatusOK, list)
		return nil
	})))
}
