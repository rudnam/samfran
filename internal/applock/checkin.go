package applock

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/space"
)

// Check-ins: a lock is only as good as the phone covering it, and whoever
// holds the phone can turn that off. So a phone with locked apps checks in
// every 15 minutes, saying whether it can still cover them, and the other
// person is told (by push, "lock_check") when:
//   - covering stops (usage access or display over other apps turned off,
//     the watcher stopped, or signing out): state "off", with the reason;
//   - it starts again: state "on";
//   - the watcher was stopped between two check-ins and has started again
//     since (force-stopped, say; a phone restarting doesn't count): state
//     "restarted", with when;
//   - a phone hasn't checked in for a day (uninstalled, or the watcher
//     can't run): state "quiet". A phone that's idle or offline overnight
//     keeps its watcher running and catches up, so that alone says nothing.
const (
	// QuietAfter is how long without a check-in before the other person is told.
	QuietAfter = 24 * time.Hour
	// A watcher that started this long after the phone did was started by the phone starting.
	bootSlack = 5 * time.Minute
	// A watcher that started this long after the last check-in was restarted in between.
	restartSlack = 2 * time.Minute
)

// Phone is how a person's phone last said it was covering their locked apps.
type Phone struct {
	Mine        bool      `json:"mine"`
	Owner       string    `json:"owner"`
	Covering    bool      `json:"covering"`
	UsageAccess bool      `json:"usage_access"`
	Overlay     bool      `json:"overlay"`
	Watching    bool      `json:"watching"`
	Reason      string    `json:"reason"`
	CheckedAt   time.Time `json:"checked_at"`
	// Quiet: no check-in for QuietAfter.
	Quiet bool `json:"quiet"`
}

func (s *Store) Phones(ctx context.Context, spaceID, me int64) ([]Phone, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT c.user_id, coalesce(m.display_name, ''), u.email, c.usage_access, c.overlay, c.watching, c.reason, c.checked_at
		FROM lock_checkins c JOIN users u ON u.id = c.user_id LEFT JOIN space_members m ON m.user_id = c.user_id
		WHERE c.space_id = $1 AND EXISTS (SELECT 1 FROM app_locks l WHERE l.user_id = c.user_id)
		ORDER BY c.user_id <> $2`, spaceID, me)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Phone{}
	for rows.Next() {
		var p Phone
		var who int64
		var name, email string
		if err := rows.Scan(&who, &name, &email, &p.UsageAccess, &p.Overlay, &p.Watching, &p.Reason, &p.CheckedAt); err != nil {
			return nil, err
		}
		p.Mine = who == me
		p.Owner = space.DisplayName(name, email)
		p.Covering = p.UsageAccess && p.Overlay && p.Watching
		p.Quiet = time.Since(p.CheckedAt) > QuietAfter
		out = append(out, p)
	}
	return out, rows.Err()
}

type checkInput struct {
	UsageAccess bool `json:"usage_access"`
	Overlay     bool `json:"overlay"`
	Watching    bool `json:"watching"`
	// WatchingSince is when the watcher started; BootedAt when the phone did.
	WatchingSince *time.Time `json:"watching_since"`
	BootedAt      *time.Time `json:"booted_at"`
	// Reason: signed_out when it's stopping on purpose; updated when the
	// watcher has started again because the app was updated (not a stop).
	Reason string `json:"reason"`
}

type checkin struct {
	usage, overlay, watching bool
	since                    *time.Time
	checkedAt                time.Time
	quietTold                bool
}

func (c checkin) covering() bool { return c.usage && c.overlay && c.watching }

// checkIn is PUT /api/locks/check-in: the phone says how it's covering.
func (h *Handler) checkIn(w http.ResponseWriter, r *http.Request) error {
	var in checkInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	updated := in.Reason == "updated"
	if in.Reason != "signed_out" {
		in.Reason = ""
	}
	ctx, sp, user := r.Context(), space.SpaceFrom(r), me(r)
	var prev checkin
	err := h.store.db.QueryRowContext(ctx, `SELECT usage_access, overlay, watching, watching_since, checked_at, quiet_told FROM lock_checkins WHERE user_id = $1`, user).
		Scan(&prev.usage, &prev.overlay, &prev.watching, &prev.since, &prev.checkedAt, &prev.quietTold)
	first := errors.Is(err, sql.ErrNoRows)
	if err != nil && !first {
		return err
	}
	if _, err := h.store.db.ExecContext(ctx, `
		INSERT INTO lock_checkins (user_id, space_id, usage_access, overlay, watching, reason, watching_since)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (user_id) DO UPDATE SET space_id = $2, usage_access = $3, overlay = $4, watching = $5, reason = $6,
			watching_since = $7, checked_at = now(), quiet_told = false`,
		user, sp, in.UsageAccess, in.Overlay, in.Watching, in.Reason, in.WatchingSince); err != nil {
		return err
	}
	now := checkin{usage: in.UsageAccess, overlay: in.Overlay, watching: in.Watching, since: in.WatchingSince}
	if alert := h.compare(prev, now, first || updated && now.covering() && prev.covering(), in); alert != nil {
		h.tellPartner(ctx, sp, user, alert)
	}
	h.sweepQuiet(ctx)
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// compare says what the other person should hear about this check-in, if anything.
func (h *Handler) compare(prev, now checkin, first bool, in checkInput) map[string]string {
	if first {
		return nil
	}
	switch {
	case prev.covering() && !now.covering():
		reason := in.Reason
		switch {
		case reason != "":
		case !now.usage:
			reason = "usage_access"
		case !now.overlay:
			reason = "overlay"
		default:
			reason = "stopped"
		}
		return map[string]string{"state": "off", "reason": reason}
	case !prev.covering() && now.covering():
		return map[string]string{"state": "on"}
	case now.covering() && prev.quietTold:
		return map[string]string{"state": "on"}
	case now.covering() && now.since != nil && now.since.After(prev.checkedAt.Add(restartSlack)):
		// The watcher started after the last check-in: it was stopped in between,
		// unless the phone itself had just started.
		if in.BootedAt != nil && now.since.Sub(*in.BootedAt) < bootSlack && in.BootedAt.After(prev.checkedAt) {
			return nil
		}
		return map[string]string{"state": "restarted", "from": prev.checkedAt.UTC().Format(time.RFC3339), "to": now.since.UTC().Format(time.RFC3339)}
	}
	return nil
}

// tellPartner pushes a lock_check to the other person, if there are locks to cover.
func (h *Handler) tellPartner(ctx context.Context, spaceID, user int64, data map[string]string) {
	var locked bool
	if err := h.store.db.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM app_locks WHERE user_id = $1)`, user).Scan(&locked); err != nil || !locked {
		return
	}
	partner, ok, err := h.store.partner(ctx, spaceID, user)
	if err != nil || !ok {
		return
	}
	data["type"] = "lock_check"
	data["who"] = h.store.name(ctx, user)
	h.notify(ctx, partner.id, data)
}

// sweepQuiet tells the other person about phones with locks that were
// covering but haven't checked in for QuietAfter, once each. It runs as
// the phones check in (every 15 minutes each), so it needs no timer.
func (h *Handler) sweepQuiet(ctx context.Context) {
	rows, err := h.store.db.QueryContext(ctx, `
		UPDATE lock_checkins c SET quiet_told = true
		WHERE NOT quiet_told AND checked_at < now() - make_interval(secs => $1)
			AND usage_access AND overlay AND watching
			AND EXISTS (SELECT 1 FROM app_locks l WHERE l.user_id = c.user_id)
		RETURNING user_id, space_id, checked_at`, QuietAfter.Seconds())
	if err != nil {
		slog.WarnContext(ctx, "lock check-ins: quiet", "err", err)
		return
	}
	type quiet struct {
		user, space int64
		since       time.Time
	}
	var found []quiet
	for rows.Next() {
		var q quiet
		if rows.Scan(&q.user, &q.space, &q.since) == nil {
			found = append(found, q)
		}
	}
	rows.Close()
	for _, q := range found {
		h.tellPartner(ctx, q.space, q.user, map[string]string{"state": "quiet", "since": q.since.UTC().Format(time.RFC3339)})
	}
}
