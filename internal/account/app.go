package account

// The Android app (android/) isn't a browser (and builds from before the
// native app ran the web app from their own files), so its requests to the
// API are cross-site and carry no cookie. Instead it
// sends the X-App-Client header when signing in, gets the session token
// in the JSON answer, and sends it back as "Authorization: Bearer <token>".
// Bearer tokens are never sent by a browser on its own, so these requests
// need no CSRF protection.
//
// Google doesn't allow signing in inside an app's web view, so the app
// opens the phone's browser instead, like OAuth for native apps (RFC 8252,
// RFC 7636): it keeps a random secret and opens /api/auth/google with the
// secret's hash (app_challenge). When Google sends the browser back, the
// server redirects it to <package>://auth?code=…, which Android hands to the
// app, and the app trades the code and its secret for a session token. A
// code another app intercepts is useless without the secret.
//
// The pull-request preview build installs beside the real app under
// <package>.preview, so it sends app_variant=preview and is sent back to
// its own scheme; otherwise the real app would get its sign-in.

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/rudnam/samfran/internal/httpx"
)

const (
	// appClientHeader marks requests from the Android app.
	appClientHeader = "X-App-Client"
	// appSessionTTL: people expect an app to stay signed in.
	appSessionTTL = 365 * 24 * time.Hour
	// appCodeTTL is how long the app has to redeem a code; it needs seconds.
	appCodeTTL = 5 * time.Minute
)

var errBadAppCode = errors.New("app code invalid, expired, or for another secret")

// fromApp reports whether the Android app sent the request.
func fromApp(r *http.Request) bool { return r.Header.Get(appClientHeader) != "" }

// bearerToken returns the app's session token, or "".
func bearerToken(r *http.Request) string {
	token, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	return token
}

// appChallenge returns the app's challenge, base64url SHA-256, or nil if
// the request has none or a malformed one.
func appChallenge(s string) []byte {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil || len(b) != sha256.Size {
		return nil
	}
	return b
}

// appVariants are the builds that may ask to be sent back: "" is the real
// app, and each other one installs as <package>.<variant>.
var appVariants = map[string]bool{"": true, "preview": true}

// appRedirect is where Google sign-in sends the browser back to the app's
// build variant.
func (h *Handler) appRedirect(variant string, q url.Values) string {
	scheme := h.cfg.AppScheme
	if variant != "" {
		scheme += "." + variant
	}
	return scheme + "://auth?" + q.Encode()
}

// appToken trades a code from the Google sign-in and the secret behind its
// challenge for a session token.
func (h *Handler) appToken(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Code     string `json:"code"`
		Verifier string `json:"verifier"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	userID, err := h.store.redeemAppCode(r.Context(), hashToken(in.Code), in.Verifier)
	if errors.Is(err, errBadAppCode) {
		return httpx.Errorf(http.StatusBadRequest, "that sign-in took too long; try again")
	}
	if err != nil {
		return err
	}
	token, hash, err := newSessionToken()
	if err != nil {
		return err
	}
	if err := h.store.createSession(r.Context(), userID, hash, h.now().Add(appSessionTTL)); err != nil {
		return err
	}
	u, err := h.store.userBySession(r.Context(), hash)
	if err != nil {
		return err
	}
	me := h.meFor(u)
	me.Token = token
	httpx.WriteJSON(w, http.StatusOK, me)
	return nil
}

// createAppCode stores a one-time code for the user, bound to challenge.
func (s *Store) createAppCode(ctx context.Context, userID int64, challenge []byte, expires time.Time) (string, error) {
	code, hash, err := newSessionToken() // same shape: random, stored hashed
	if err != nil {
		return "", err
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM app_codes WHERE expires_at <= now()`); err != nil {
		return "", err
	}
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO app_codes (code_hash, user_id, challenge, expires_at) VALUES ($1, $2, $3, $4)`,
		hash, userID, challenge, expires)
	return code, err
}

// redeemAppCode uses up a code and returns its user if verifier matches its
// challenge. A wrong verifier still uses the code up.
func (s *Store) redeemAppCode(ctx context.Context, codeHash []byte, verifier string) (int64, error) {
	var userID int64
	var challenge []byte
	err := s.db.QueryRowContext(ctx,
		`DELETE FROM app_codes WHERE code_hash = $1 AND expires_at > now() RETURNING user_id, challenge`,
		codeHash).Scan(&userID, &challenge)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, errBadAppCode
	}
	if err != nil {
		return 0, err
	}
	sum := sha256.Sum256([]byte(verifier))
	if subtle.ConstantTimeCompare(sum[:], challenge) != 1 {
		return 0, errBadAppCode
	}
	return userID, nil
}
