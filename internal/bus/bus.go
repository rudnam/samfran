// Package bus keeps where each person gets off the bus, for the Android
// app's wake-up alarm (android/…/BusTripService.java): during a trip the
// phone watches its own position and rings when it gets within
// alert_meters of the stop, and buzzes the way buzz says. The server only
// stores the stops; positions never leave the phone. Choosing a stop can
// search for it by name (search.go).
//
// A person can keep several stops (stops.go), as they get off at different
// ones after different shifts: a stop can be the one for some kinds of
// shift, and the apps pick it for the shift that's ending. /api/bus/stop,
// which older apps use, is the stop used last.
package bus

import (
	"context"
	"database/sql"
	"net/http"
	"slices"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/httpx"
)

// Stop is where the person gets off, as /api/bus/stop has it: the stop used
// last. Set is false until they choose one.
type Stop struct {
	Set         bool    `json:"set"`
	Name        string  `json:"name"`
	Lat         float64 `json:"lat"`
	Lng         float64 `json:"lng"`
	AlertMeters int     `json:"alert_meters"`
	// Buzz is how the phone buzzes at the stop: one of Buzzes.
	Buzz string `json:"buzz"`
}

// Buzzes are the ways the phone can buzz: "gentle" (short buzzes at the
// phone's usual strength, as it always did), "strong" (full strength,
// longer buzzes; the default) and "strongest" (full strength, hardly a
// pause). The patterns themselves are in the apps.
var Buzzes = []string{"gentle", "strong", "strongest"}

const defaultBuzz = "strong"

type Handler struct {
	db *sql.DB
	// Stops finds stops by name (search.go).
	Stops *Searcher
	// Log and SpaceOf, if set, record a trip starting and ending in the
	// history (internal/activity), so the other person can be told. Stops
	// are the person's own, so the routes don't need a space; SpaceOf finds it.
	Log     func(ctx context.Context, spaceID, userID int64, via, kind string, details map[string]any)
	SpaceOf func(ctx context.Context, userID int64) (int64, error)
}

func NewHandler(conn *sql.DB) *Handler { return &Handler{db: conn, Stops: NewSearcher()} }

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	route := func(pattern string, f httpx.HandlerFunc) { mux.Handle(pattern, requireUser(f)) }
	route("GET /api/bus/stop", h.get)
	route("PUT /api/bus/stop", h.put)
	route("GET /api/bus/stops", h.listStops)
	route("POST /api/bus/stops", h.addStop)
	route("PUT /api/bus/stops/{id}", h.changeStop)
	route("DELETE /api/bus/stops/{id}", h.deleteStop)
	route("POST /api/bus/stops/{id}/use", h.useStop)
	route("POST /api/bus/stops/{id}/done", h.rideDone)
	route("PUT /api/bus/buzz", h.setBuzz)
	route("GET /api/bus/search", h.search)
}

// get is the stop used last, for older apps.
func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	all, err := h.load(r.Context(), h.db, account.UserFrom(r).ID)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, all.legacy())
	return nil
}

// put changes the stop used last (or adds the first), for older apps; it
// leaves the stop's shifts as they were.
func (h *Handler) put(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		stopInput
		// Left out (as older apps do), the buzz stays as it was.
		Buzz *string `json:"buzz"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	if err := in.check(); err != nil {
		return err
	}
	if in.Buzz != nil && !slices.Contains(Buzzes, *in.Buzz) {
		return errBuzz
	}
	user := account.UserFrom(r).ID
	all, err := h.change(r.Context(), user, func(tx *sql.Tx, all Stops) error {
		if all.LastID == 0 {
			_, err := tx.ExecContext(r.Context(), `INSERT INTO bus_saved_stops (user_id, name, lat, lng, alert_meters) VALUES ($1, $2, $3, $4, $5)`,
				user, in.Name, *in.Lat, *in.Lng, in.AlertMeters)
			return err
		}
		_, err := tx.ExecContext(r.Context(), `UPDATE bus_saved_stops SET name = $3, lat = $4, lng = $5, alert_meters = $6 WHERE user_id = $1 AND id = $2`,
			user, all.LastID, in.Name, *in.Lat, *in.Lng, in.AlertMeters)
		return err
	}, settings{Buzz: in.Buzz})
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, all.legacy())
	return nil
}
