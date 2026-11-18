package bus

import (
	"cmp"
	"context"
	"database/sql"
	"errors"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/lib/pq"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/httpx"
)

// SavedStop is one of a person's stops.
type SavedStop struct {
	ID          int64   `json:"id"`
	Name        string  `json:"name"`
	Lat         float64 `json:"lat"`
	Lng         float64 `json:"lng"`
	AlertMeters int     `json:"alert_meters"`
	// Shifts are the kinds of shift this is the stop for (ShiftKinds); each
	// kind is on at most one of a person's stops.
	Shifts []string `json:"shifts"`
}

// Stops are a person's stops, oldest first, and how their phone buzzes.
type Stops struct {
	Stops []SavedStop `json:"stops"`
	// LastID is the stop used last (or the only one): the one to go to when
	// no shift says which. 0 when there are none.
	LastID int64  `json:"last_id"`
	Buzz   string `json:"buzz"`
}

// ShiftKinds are the kinds of working shift a stop can be for, as
// internal/shift labels them by when they start.
var ShiftKinds = []string{"Early", "Middle", "Late", "Night"}

// MaxStops is how many stops a person can keep.
const MaxStops = 10

var (
	errBuzz   = httpx.Errorf(http.StatusBadRequest, "buzz must be gentle, strong or strongest")
	errNoStop = httpx.Errorf(http.StatusNotFound, "no such stop")
)

func (s Stops) legacy() Stop {
	st := Stop{AlertMeters: 800, Buzz: s.Buzz}
	for _, x := range s.Stops {
		if x.ID == s.LastID {
			st = Stop{Set: true, Name: x.Name, Lat: x.Lat, Lng: x.Lng, AlertMeters: x.AlertMeters, Buzz: s.Buzz}
		}
	}
	return st
}

type querier interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func (h *Handler) load(ctx context.Context, q querier, user int64) (Stops, error) {
	all := Stops{Stops: []SavedStop{}, Buzz: defaultBuzz}
	if err := q.QueryRowContext(ctx, `SELECT buzz FROM bus_stops WHERE user_id = $1`, user).Scan(&all.Buzz); err != nil && !errors.Is(err, sql.ErrNoRows) {
		return all, err
	}
	// The one used last comes first here, then they're put in the order they were added.
	rows, err := q.QueryContext(ctx, `SELECT id, name, lat, lng, alert_meters, shifts FROM bus_saved_stops
		WHERE user_id = $1 ORDER BY used_at DESC, id DESC`, user)
	if err != nil {
		return all, err
	}
	defer rows.Close()
	for rows.Next() {
		var x SavedStop
		if err := rows.Scan(&x.ID, &x.Name, &x.Lat, &x.Lng, &x.AlertMeters, (*pq.StringArray)(&x.Shifts)); err != nil {
			return all, err
		}
		if all.LastID == 0 {
			all.LastID = x.ID
		}
		x.Shifts = inOrder(x.Shifts)
		all.Stops = append(all.Stops, x)
	}
	if err := rows.Err(); err != nil {
		return all, err
	}
	slices.SortFunc(all.Stops, func(a, b SavedStop) int { return cmp.Compare(a.ID, b.ID) })
	return all, nil
}

// inOrder puts kinds of shift in the order of the day, never nil.
func inOrder(kinds []string) []string {
	out := []string{}
	for _, k := range ShiftKinds {
		if slices.Contains(kinds, k) {
			out = append(out, k)
		}
	}
	return out
}

// change runs f in a transaction with the person's stops as they were, then
// sets the buzz when given, and copies the stop used last into bus_stops
// (for older apps, and for the previous release while this one rolls out).
func (h *Handler) change(ctx context.Context, user int64, f func(*sql.Tx, Stops) error, buzz *string) (Stops, error) {
	tx, err := h.db.BeginTx(ctx, nil)
	if err != nil {
		return Stops{}, err
	}
	defer tx.Rollback()
	// One change at a time for each person.
	if _, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(hashtext('bus_saved_stops'), $1::int)`, user); err != nil {
		return Stops{}, err
	}
	before, err := h.load(ctx, tx, user)
	if err != nil {
		return Stops{}, err
	}
	if err := f(tx, before); err != nil {
		return Stops{}, err
	}
	after, err := h.load(ctx, tx, user)
	if err != nil {
		return Stops{}, err
	}
	if buzz != nil {
		after.Buzz = *buzz
	}
	if last := after.legacy(); last.Set {
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO bus_stops (user_id, name, lat, lng, alert_meters, buzz) VALUES ($1, $2, $3, $4, $5, $6)
			ON CONFLICT (user_id) DO UPDATE SET name = $2, lat = $3, lng = $4, alert_meters = $5, buzz = $6, updated_at = now()`,
			user, last.Name, last.Lat, last.Lng, last.AlertMeters, after.Buzz); err != nil {
			return Stops{}, err
		}
	} else if buzz != nil {
		// No stop to keep it with yet.
		return Stops{}, httpx.Errorf(http.StatusBadRequest, "choose a stop first")
	}
	return after, tx.Commit()
}

// stopInput is a stop as the apps send it.
type stopInput struct {
	Name        string   `json:"name"`
	Lat         *float64 `json:"lat"`
	Lng         *float64 `json:"lng"`
	AlertMeters int      `json:"alert_meters"`
}

// check tidies the name and fills in the distance, or says what's wrong.
func (in *stopInput) check() error {
	in.Name = strings.TrimSpace(in.Name)
	switch {
	case in.Lat == nil || in.Lng == nil || *in.Lat < -90 || *in.Lat > 90 || *in.Lng < -180 || *in.Lng > 180:
		return httpx.Errorf(http.StatusBadRequest, "choose where the stop is")
	case utf8.RuneCountInString(in.Name) > 80:
		return httpx.Errorf(http.StatusBadRequest, "a stop's name must be at most 80 characters")
	case in.AlertMeters == 0:
		in.AlertMeters = 800
	case in.AlertMeters < 200 || in.AlertMeters > 5000:
		return httpx.Errorf(http.StatusBadRequest, "ring between 200 m and 5 km before the stop")
	}
	return nil
}

// savedInput is a stop with the kinds of shift it's for.
type savedInput struct {
	stopInput
	// Left out, a changed stop keeps the ones it had (a new one has none).
	Shifts *[]string `json:"shifts"`
}

func (h *Handler) readSaved(w http.ResponseWriter, r *http.Request) (savedInput, error) {
	var in savedInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return in, err
	}
	if err := in.check(); err != nil {
		return in, err
	}
	if in.Shifts != nil {
		for _, k := range *in.Shifts {
			if !slices.Contains(ShiftKinds, k) {
				return in, httpx.Errorf(http.StatusBadRequest, "a stop can be for Early, Middle, Late or Night shifts")
			}
		}
		kinds := inOrder(*in.Shifts)
		in.Shifts = &kinds
	}
	return in, nil
}

// takeShifts takes the kinds of shift from the person's other stops: each is on one stop at most.
func takeShifts(ctx context.Context, tx *sql.Tx, user, id int64, kinds *[]string) error {
	if kinds == nil || len(*kinds) == 0 {
		return nil
	}
	_, err := tx.ExecContext(ctx, `UPDATE bus_saved_stops SET shifts = ARRAY(SELECT unnest(shifts) EXCEPT SELECT unnest($3::text[]))
		WHERE user_id = $1 AND id <> $2 AND shifts && $3::text[]`, user, id, pq.Array(*kinds))
	return err
}

func (h *Handler) listStops(w http.ResponseWriter, r *http.Request) error {
	all, err := h.load(r.Context(), h.db, account.UserFrom(r).ID)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, all)
	return nil
}

func (h *Handler) addStop(w http.ResponseWriter, r *http.Request) error {
	in, err := h.readSaved(w, r)
	if err != nil {
		return err
	}
	user := account.UserFrom(r).ID
	kinds := []string{}
	if in.Shifts != nil {
		kinds = *in.Shifts
	}
	all, err := h.change(r.Context(), user, func(tx *sql.Tx, before Stops) error {
		if len(before.Stops) >= MaxStops {
			return httpx.Errorf(http.StatusBadRequest, "you can keep up to %d stops", MaxStops)
		}
		// A new stop isn't the one used last, unless it's the first.
		var id int64
		if err := tx.QueryRowContext(r.Context(), `
			INSERT INTO bus_saved_stops (user_id, name, lat, lng, alert_meters, shifts, used_at)
			VALUES ($1, $2, $3, $4, $5, $6, CASE WHEN $7 THEN now() ELSE '-infinity' END) RETURNING id`,
			user, in.Name, *in.Lat, *in.Lng, in.AlertMeters, pq.Array(kinds), len(before.Stops) == 0).Scan(&id); err != nil {
			return err
		}
		return takeShifts(r.Context(), tx, user, id, &kinds)
	}, nil)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusCreated, all)
	return nil
}

func pathID(r *http.Request) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		return 0, errNoStop
	}
	return id, nil
}

// changeOne runs f on one of the person's stops: another person's is a 404.
func (h *Handler) changeOne(w http.ResponseWriter, r *http.Request, f func(tx *sql.Tx, user, id int64) error) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	user := account.UserFrom(r).ID
	all, err := h.change(r.Context(), user, func(tx *sql.Tx, before Stops) error {
		if !slices.ContainsFunc(before.Stops, func(x SavedStop) bool { return x.ID == id }) {
			return errNoStop
		}
		return f(tx, user, id)
	}, nil)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, all)
	return nil
}

func (h *Handler) changeStop(w http.ResponseWriter, r *http.Request) error {
	in, err := h.readSaved(w, r)
	if err != nil {
		return err
	}
	return h.changeOne(w, r, func(tx *sql.Tx, user, id int64) error {
		if _, err := tx.ExecContext(r.Context(), `UPDATE bus_saved_stops SET name = $3, lat = $4, lng = $5, alert_meters = $6,
			shifts = COALESCE($7, shifts) WHERE user_id = $1 AND id = $2`,
			user, id, in.Name, *in.Lat, *in.Lng, in.AlertMeters, nullArray(in.Shifts)); err != nil {
			return err
		}
		return takeShifts(r.Context(), tx, user, id, in.Shifts)
	})
}

func nullArray(kinds *[]string) any {
	if kinds == nil {
		return nil
	}
	return pq.Array(*kinds)
}

func (h *Handler) deleteStop(w http.ResponseWriter, r *http.Request) error {
	return h.changeOne(w, r, func(tx *sql.Tx, user, id int64) error {
		_, err := tx.ExecContext(r.Context(), `DELETE FROM bus_saved_stops WHERE user_id = $1 AND id = $2`, user, id)
		return err
	})
}

// useStop says a trip to the stop has started: it's the one used last.
func (h *Handler) useStop(w http.ResponseWriter, r *http.Request) error {
	return h.changeOne(w, r, func(tx *sql.Tx, user, id int64) error {
		_, err := tx.ExecContext(r.Context(), `UPDATE bus_saved_stops SET used_at = now() WHERE user_id = $1 AND id = $2`, user, id)
		return err
	})
}

func (h *Handler) setBuzz(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Buzz string `json:"buzz"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	if !slices.Contains(Buzzes, in.Buzz) {
		return errBuzz
	}
	all, err := h.change(r.Context(), account.UserFrom(r).ID, func(*sql.Tx, Stops) error { return nil }, &in.Buzz)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, all)
	return nil
}
