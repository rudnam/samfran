// Package push reaches a person's phones at once, through Firebase Cloud
// Messaging (FCM): the Android app registers its token here
// (PUT /api/push/token), and features call Notifier.Notify with a few
// strings of data, which the app turns into a notification. Locked apps
// (internal/applock) use it, since asking to open one can't wait, and so
// does the log of changes (internal/activity), so the other person hears
// of a change at once rather than at the phone's half-hourly check.
//
// Without FIREBASE_SERVICE_ACCOUNT nothing is sent; everything else works,
// and the app finds out when it next asks the server.
package push

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/httpx"
)

// ErrUnregistered is a token Firebase no longer knows (the app was
// uninstalled, or its data cleared): it's forgotten.
var ErrUnregistered = errors.New("push token is no longer registered")

// Sender delivers one message's data to one phone.
type Sender interface {
	Send(ctx context.Context, token string, data map[string]string) error
}

// Notifier sends to every phone of a person. A nil Notifier, or one without
// a Sender, sends nothing.
type Notifier struct {
	db     *sql.DB
	sender Sender
}

func NewNotifier(conn *sql.DB, sender Sender) *Notifier { return &Notifier{db: conn, sender: sender} }

// Notify sends data to the user's phones in the background: the change that
// caused it has already succeeded, and a phone that can't be reached finds
// out when the app next asks the server.
func (n *Notifier) Notify(ctx context.Context, userID int64, data map[string]string) {
	if n == nil || n.sender == nil {
		return
	}
	ctx = context.WithoutCancel(ctx)
	go func() {
		ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		rows, err := n.db.QueryContext(ctx, `SELECT token FROM push_tokens WHERE user_id = $1`, userID)
		if err != nil {
			slog.WarnContext(ctx, "push: tokens", "err", err)
			return
		}
		var tokens []string
		for rows.Next() {
			var t string
			if rows.Scan(&t) == nil {
				tokens = append(tokens, t)
			}
		}
		rows.Close()
		for _, t := range tokens {
			err := n.sender.Send(ctx, t, data)
			if errors.Is(err, ErrUnregistered) {
				n.db.ExecContext(ctx, `DELETE FROM push_tokens WHERE token = $1`, t)
			} else if err != nil {
				slog.WarnContext(ctx, "push: send", "type", data["type"], "err", err)
			}
		}
	}()
}

// ---------- HTTP ----------

type Handler struct{ db *sql.DB }

func NewHandler(conn *sql.DB) *Handler { return &Handler{db: conn} }

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	mux.Handle("PUT /api/push/token", requireUser(httpx.HandlerFunc(h.save)))
	mux.Handle("DELETE /api/push/token", requireUser(httpx.HandlerFunc(h.forget)))
}

type tokenInput struct {
	Token string `json:"token"`
}

func readToken(w http.ResponseWriter, r *http.Request) (string, error) {
	var in tokenInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return "", err
	}
	in.Token = strings.TrimSpace(in.Token)
	if in.Token == "" || len(in.Token) > 4096 {
		return "", httpx.Errorf(http.StatusBadRequest, "a push token is needed")
	}
	return in.Token, nil
}

// save keeps the phone's token for the signed-in person (taking it from
// whoever had it before: the phone signed in as someone else).
func (h *Handler) save(w http.ResponseWriter, r *http.Request) error {
	token, err := readToken(w, r)
	if err != nil {
		return err
	}
	if _, err := h.db.ExecContext(r.Context(), `
		INSERT INTO push_tokens (token, user_id) VALUES ($1, $2)
		ON CONFLICT (token) DO UPDATE SET user_id = $2, updated_at = now()`, token, account.UserFrom(r).ID); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// forget drops the phone's token: it's signing out.
func (h *Handler) forget(w http.ResponseWriter, r *http.Request) error {
	token, err := readToken(w, r)
	if err != nil {
		return err
	}
	if _, err := h.db.ExecContext(r.Context(), `DELETE FROM push_tokens WHERE token = $1 AND user_id = $2`, token, account.UserFrom(r).ID); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
