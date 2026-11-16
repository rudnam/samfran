package space

// Invite links: a member makes one (Us tab), sends it to the other person,
// who opens /join/<secret>, signs up (with Google or a password, any email)
// and lands in the space. A link works once and for seven days; making a new
// one replaces the old. Opening it only stores the secret in a cookie: the
// sign-up checks it (SignupAllowedByLink) and spends it (JoinByLink).

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
	"net/http"
	"net/url"
	"time"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/httpx"
)

const (
	linkTTL      = 7 * 24 * time.Hour
	InviteCookie = "samfran_invite"
)

func hashLink(secret string) []byte {
	h := sha256.Sum256([]byte(secret))
	return h[:]
}

// Link is the space's open invite link, without its secret.
type Link struct {
	ExpiresAt time.Time `json:"expires_at"`
}

// createLink makes a new link for the space, replacing unused ones.
func (s *Store) createLink(ctx context.Context, spaceID, by int64) (string, time.Time, error) {
	b := make([]byte, 24)
	if _, err := rand.Read(b); err != nil {
		return "", time.Time{}, err
	}
	secret := base64.RawURLEncoding.EncodeToString(b)
	expires := time.Now().Add(linkTTL)
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", time.Time{}, err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM space_invite_links WHERE space_id = $1 AND used_at IS NULL`, spaceID); err != nil {
		return "", time.Time{}, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO space_invite_links (token_hash, space_id, created_by, expires_at) VALUES ($1, $2, $3, $4)`,
		hashLink(secret), spaceID, by, expires); err != nil {
		return "", time.Time{}, err
	}
	return secret, expires, tx.Commit()
}

func (s *Store) cancelLinks(ctx context.Context, spaceID int64) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM space_invite_links WHERE space_id = $1 AND used_at IS NULL`, spaceID)
	return err
}

func (s *Store) openLink(ctx context.Context, spaceID int64) (*Link, error) {
	var l Link
	err := s.db.QueryRowContext(ctx, `SELECT expires_at FROM space_invite_links
		WHERE space_id = $1 AND used_at IS NULL AND expires_at > now() ORDER BY created_at DESC LIMIT 1`, spaceID).Scan(&l.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &l, err
}

// linkSpace returns the space a usable link joins, and who made it.
func (s *Store) linkSpace(ctx context.Context, secret string) (spaceID int64, inviter string, err error) {
	var name, email sql.NullString
	err = s.db.QueryRowContext(ctx, `
		SELECT l.space_id, m.display_name, u.email FROM space_invite_links l
		LEFT JOIN users u ON u.id = l.created_by LEFT JOIN space_members m ON m.user_id = l.created_by
		WHERE l.token_hash = $1 AND l.used_at IS NULL AND l.expires_at > now()`, hashLink(secret)).Scan(&spaceID, &name, &email)
	if email.Valid {
		inviter = DisplayName(name.String, email.String)
	}
	return spaceID, inviter, err
}

// SignupAllowedByLink reports whether the request carries a usable invite
// link (from the cookie /join sets).
func (s *Store) SignupAllowedByLink(ctx context.Context) bool {
	secret := InviteFrom(ctx)
	if secret == "" {
		return false
	}
	_, _, err := s.linkSpace(ctx, secret)
	return err == nil
}

// JoinByLink puts a new account in the space of the invite link the request
// carries, using the link up. It reports false if there's no usable link.
func (s *Store) JoinByLink(ctx context.Context, userID int64) (bool, error) {
	secret := InviteFrom(ctx)
	if secret == "" {
		return false, nil
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var spaceID int64
	err = tx.QueryRowContext(ctx, `UPDATE space_invite_links SET used_at = now(), used_by = $2
		WHERE token_hash = $1 AND used_at IS NULL AND expires_at > now() RETURNING space_id`, hashLink(secret), userID).Scan(&spaceID)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO space_members (user_id, space_id) VALUES ($1, $2)`, userID, spaceID); err != nil {
		return false, err
	}
	return true, tx.Commit()
}

type inviteKey struct{}

// WithInvite carries an invite link's secret (from its cookie) in the
// request, for sign-up.
func WithInvite(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if c, err := r.Cookie(InviteCookie); err == nil && c.Value != "" {
			r = r.WithContext(context.WithValue(r.Context(), inviteKey{}, c.Value))
		}
		next.ServeHTTP(w, r)
	})
}

// InviteFrom returns the invite secret the request carries, or "".
func InviteFrom(ctx context.Context) string {
	s, _ := ctx.Value(inviteKey{}).(string)
	return s
}

// ---------- HTTP ----------

func (h *Handler) createLink(w http.ResponseWriter, r *http.Request) error {
	if err := account.NotInDemo(r); err != nil {
		return err
	}
	secret, expires, err := h.store.createLink(r.Context(), SpaceFrom(r), account.UserFrom(r).ID)
	if err != nil {
		return err
	}
	if h.Log != nil {
		h.Log(r.Context(), SpaceFrom(r), account.UserFrom(r).ID, "app", "invite_link", nil)
	}
	httpx.WriteJSON(w, http.StatusCreated, map[string]any{"path": "/join/" + secret, "expires_at": expires})
	return nil
}

func (h *Handler) cancelLink(w http.ResponseWriter, r *http.Request) error {
	if err := h.store.cancelLinks(r.Context(), SpaceFrom(r)); err != nil {
		return err
	}
	return h.get(w, r)
}

// join is where an invite link points: it keeps the secret in a cookie
// for the sign-up, and opens the app on its join page.
func (h *Handler) join(w http.ResponseWriter, r *http.Request) {
	secret := r.PathValue("code")
	_, inviter, err := h.store.linkSpace(r.Context(), secret)
	if err != nil {
		http.Redirect(w, r, "/app/#/join?invalid=1", http.StatusFound)
		return
	}
	http.SetCookie(w, &http.Cookie{Name: InviteCookie, Value: secret, Path: "/", MaxAge: int(linkTTL.Seconds()),
		HttpOnly: true, Secure: h.SecureCookies, SameSite: http.SameSiteLaxMode})
	http.Redirect(w, r, "/app/#/join?"+url.Values{"from": {inviter}}.Encode(), http.StatusFound)
}
