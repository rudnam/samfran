// Package bus keeps where each person gets off the bus, for the Android
// app's wake-up alarm (android/…/BusTripService.java): during a trip the
// phone watches its own position and rings when it gets within
// alert_meters of the stop. The server only stores the stop; positions
// never leave the phone. Choosing the stop can search for it by name
// (search.go).
package bus

import (
	"database/sql"
	"errors"
	"net/http"
	"strings"
	"unicode/utf8"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/httpx"
)

// Stop is where the person gets off. Set is false until they choose one.
type Stop struct {
	Set         bool    `json:"set"`
	Name        string  `json:"name"`
	Lat         float64 `json:"lat"`
	Lng         float64 `json:"lng"`
	AlertMeters int     `json:"alert_meters"`
}

type Handler struct {
	db *sql.DB
	// Stops finds stops by name (search.go).
	Stops *Searcher
}

func NewHandler(conn *sql.DB) *Handler { return &Handler{db: conn, Stops: NewSearcher()} }

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	mux.Handle("GET /api/bus/stop", requireUser(httpx.HandlerFunc(h.get)))
	mux.Handle("PUT /api/bus/stop", requireUser(httpx.HandlerFunc(h.put)))
	mux.Handle("GET /api/bus/search", requireUser(httpx.HandlerFunc(h.search)))
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	st := Stop{AlertMeters: 800}
	err := h.db.QueryRowContext(r.Context(), `SELECT name, lat, lng, alert_meters FROM bus_stops WHERE user_id = $1`,
		account.UserFrom(r).ID).Scan(&st.Name, &st.Lat, &st.Lng, &st.AlertMeters)
	switch {
	case err == nil:
		st.Set = true
	case !errors.Is(err, sql.ErrNoRows):
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, st)
	return nil
}

func (h *Handler) put(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Name        string   `json:"name"`
		Lat         *float64 `json:"lat"`
		Lng         *float64 `json:"lng"`
		AlertMeters int      `json:"alert_meters"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	name := strings.TrimSpace(in.Name)
	switch {
	case in.Lat == nil || in.Lng == nil || *in.Lat < -90 || *in.Lat > 90 || *in.Lng < -180 || *in.Lng > 180:
		return httpx.Errorf(http.StatusBadRequest, "choose where the stop is")
	case utf8.RuneCountInString(name) > 80:
		return httpx.Errorf(http.StatusBadRequest, "a stop's name must be at most 80 characters")
	case in.AlertMeters == 0:
		in.AlertMeters = 800
	case in.AlertMeters < 200 || in.AlertMeters > 5000:
		return httpx.Errorf(http.StatusBadRequest, "ring between 200 m and 5 km before the stop")
	}
	if _, err := h.db.ExecContext(r.Context(), `
		INSERT INTO bus_stops (user_id, name, lat, lng, alert_meters) VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (user_id) DO UPDATE SET name = $2, lat = $3, lng = $4, alert_meters = $5, updated_at = now()`,
		account.UserFrom(r).ID, name, *in.Lat, *in.Lng, in.AlertMeters); err != nil {
		return err
	}
	return h.get(w, r)
}
