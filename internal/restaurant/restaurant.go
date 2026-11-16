// Package restaurant keeps the restaurants you've been to together, or want
// to go to: when you went, what you thought, and each person's own rating.
// A restaurant with no visits is on the want-to-go list. Everything belongs
// to the space (internal/space), so both of you see and change the same list.
package restaurant

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/lib/pq"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/db"
	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/space"
)

const (
	maxName    = 200
	maxArea    = 100
	maxCuisine = 60
	maxURL     = 2000
	maxNotes   = 5000
	maxNote    = 1000
)

// Rating is one person's rating of a restaurant.
type Rating struct {
	Name  string `json:"name"`
	Me    bool   `json:"me"`
	Stars int    `json:"stars"`
}

// Visit is a time you went.
type Visit struct {
	ID        int64  `json:"id"`
	VisitedOn string `json:"visited_on"` // YYYY-MM-DD
	Note      string `json:"note"`
	AddedBy   string `json:"added_by"`
}

type Restaurant struct {
	ID         int64     `json:"id"`
	Name       string    `json:"name"`
	Area       string    `json:"area"`
	Cuisine    string    `json:"cuisine"`
	MapURL     string    `json:"map_url"`
	Notes      string    `json:"notes"`
	AddedBy    string    `json:"added_by"`
	CreatedAt  time.Time `json:"created_at"`
	VisitCount int       `json:"visit_count"`
	LastVisit  *string   `json:"last_visit"` // YYYY-MM-DD, or null if you haven't been
	Ratings    []Rating  `json:"ratings"`
	// Visits, newest first, only when one restaurant is asked for.
	Visits []Visit `json:"visits,omitempty"`
}

// Fields are what a person types in about a restaurant.
type Fields struct {
	Name    string `json:"name"`
	Area    string `json:"area"`
	Cuisine string `json:"cuisine"`
	MapURL  string `json:"map_url"`
	Notes   string `json:"notes"`
}

// ---------- Store: every query is scoped by space ----------

type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

// list's query: restaurants with their visit count and latest visit. The
// caller adds the WHERE.
const selectRestaurants = `
	SELECT r.id, r.name, r.area, r.cuisine, r.map_url, r.notes, coalesce(m.display_name, ''), coalesce(u.email, ''), r.created_at,
		(SELECT count(*) FROM restaurant_visits v WHERE v.restaurant_id = r.id),
		(SELECT to_char(max(v.visited_on), 'YYYY-MM-DD') FROM restaurant_visits v WHERE v.restaurant_id = r.id)
	FROM restaurants r
	LEFT JOIN users u ON u.id = r.added_by
	LEFT JOIN space_members m ON m.user_id = r.added_by`

func (s *Store) query(ctx context.Context, me int64, where string, args ...any) ([]Restaurant, error) {
	rows, err := s.db.QueryContext(ctx, selectRestaurants+" WHERE "+where+" ORDER BY r.id", args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Restaurant{}
	byID := map[int64]int{}
	for rows.Next() {
		var r Restaurant
		var addedName, addedEmail string
		if err := rows.Scan(&r.ID, &r.Name, &r.Area, &r.Cuisine, &r.MapURL, &r.Notes, &addedName, &addedEmail,
			&r.CreatedAt, &r.VisitCount, &r.LastVisit); err != nil {
			return nil, err
		}
		if addedEmail != "" {
			r.AddedBy = space.DisplayName(addedName, addedEmail)
		}
		r.Ratings = []Rating{}
		byID[r.ID] = len(out)
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return out, nil
	}
	ids := make([]int64, 0, len(out))
	for _, r := range out {
		ids = append(ids, r.ID)
	}
	ratings, err := s.db.QueryContext(ctx, `
		SELECT x.restaurant_id, x.user_id, x.stars, coalesce(m.display_name, ''), u.email
		FROM restaurant_ratings x JOIN users u ON u.id = x.user_id LEFT JOIN space_members m ON m.user_id = x.user_id
		WHERE x.restaurant_id = ANY($1)
		ORDER BY x.user_id <> $2, m.joined_at, x.user_id`, pq.Array(ids), me)
	if err != nil {
		return nil, err
	}
	defer ratings.Close()
	for ratings.Next() {
		var rid, uid int64
		var rt Rating
		var name, email string
		if err := ratings.Scan(&rid, &uid, &rt.Stars, &name, &email); err != nil {
			return nil, err
		}
		rt.Name, rt.Me = space.DisplayName(name, email), uid == me
		out[byID[rid]].Ratings = append(out[byID[rid]].Ratings, rt)
	}
	return out, ratings.Err()
}

// List returns all the space's restaurants. me is the person asking, whose
// rating comes first.
func (s *Store) List(ctx context.Context, spaceID, me int64) ([]Restaurant, error) {
	return s.query(ctx, me, "r.space_id = $1", spaceID)
}

// Get returns one restaurant with its visits, or db.ErrNotFound.
func (s *Store) Get(ctx context.Context, spaceID, me, id int64) (Restaurant, error) {
	list, err := s.query(ctx, me, "r.space_id = $1 AND r.id = $2", spaceID, id)
	if err != nil {
		return Restaurant{}, err
	}
	if len(list) == 0 {
		return Restaurant{}, db.ErrNotFound
	}
	r := list[0]
	rows, err := s.db.QueryContext(ctx, `
		SELECT v.id, to_char(v.visited_on, 'YYYY-MM-DD'), v.note, coalesce(m.display_name, ''), coalesce(u.email, '')
		FROM restaurant_visits v LEFT JOIN users u ON u.id = v.added_by LEFT JOIN space_members m ON m.user_id = v.added_by
		WHERE v.restaurant_id = $1 ORDER BY v.visited_on DESC, v.id DESC`, id)
	if err != nil {
		return r, err
	}
	defer rows.Close()
	r.Visits = []Visit{}
	for rows.Next() {
		var v Visit
		var name, email string
		if err := rows.Scan(&v.ID, &v.VisitedOn, &v.Note, &name, &email); err != nil {
			return r, err
		}
		if email != "" {
			v.AddedBy = space.DisplayName(name, email)
		}
		r.Visits = append(r.Visits, v)
	}
	return r, rows.Err()
}

func (s *Store) Create(ctx context.Context, spaceID, by int64, f Fields) (int64, error) {
	var id int64
	err := s.db.QueryRowContext(ctx, `
		INSERT INTO restaurants (space_id, name, area, cuisine, map_url, notes, added_by)
		VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING id`,
		spaceID, f.Name, f.Area, f.Cuisine, f.MapURL, f.Notes, by).Scan(&id)
	return id, err
}

func (s *Store) Update(ctx context.Context, spaceID, id int64, f Fields) error {
	res, err := s.db.ExecContext(ctx, `
		UPDATE restaurants SET name = $3, area = $4, cuisine = $5, map_url = $6, notes = $7, updated_at = now()
		WHERE id = $1 AND space_id = $2`, id, spaceID, f.Name, f.Area, f.Cuisine, f.MapURL, f.Notes)
	return oneRow(res, err)
}

func (s *Store) Delete(ctx context.Context, spaceID, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM restaurants WHERE id = $1 AND space_id = $2`, id, spaceID)
	return oneRow(res, err)
}

// AddVisit logs a visit to one of the space's restaurants.
func (s *Store) AddVisit(ctx context.Context, spaceID, by, id int64, on, note string) error {
	res, err := s.db.ExecContext(ctx, `
		INSERT INTO restaurant_visits (restaurant_id, visited_on, note, added_by)
		SELECT id, $3, $4, $5 FROM restaurants WHERE id = $1 AND space_id = $2`, id, spaceID, on, note, by)
	return oneRow(res, err)
}

func (s *Store) DeleteVisit(ctx context.Context, spaceID, id, visitID int64) error {
	res, err := s.db.ExecContext(ctx, `
		DELETE FROM restaurant_visits v USING restaurants r
		WHERE v.id = $3 AND v.restaurant_id = r.id AND r.id = $1 AND r.space_id = $2`, id, spaceID, visitID)
	return oneRow(res, err)
}

// Rate sets the user's rating of one of the space's restaurants; 0 clears it.
func (s *Store) Rate(ctx context.Context, spaceID, userID, id int64, stars int) error {
	var exists bool
	if err := s.db.QueryRowContext(ctx,
		`SELECT EXISTS (SELECT 1 FROM restaurants WHERE id = $1 AND space_id = $2)`, id, spaceID).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return db.ErrNotFound
	}
	if stars == 0 {
		_, err := s.db.ExecContext(ctx, `DELETE FROM restaurant_ratings WHERE restaurant_id = $1 AND user_id = $2`, id, userID)
		return err
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO restaurant_ratings (restaurant_id, user_id, stars) VALUES ($1, $2, $3)
		ON CONFLICT (restaurant_id, user_id) DO UPDATE SET stars = $3, updated_at = now()`, id, userID, stars)
	return err
}

func oneRow(res sql.Result, err error) error {
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil {
		return err
	} else if n == 0 {
		return db.ErrNotFound
	}
	return nil
}

// ---------- HTTP ----------

type Handler struct {
	// Log, if set, records changes for the Us tab's history (internal/activity).
	Log   func(ctx context.Context, spaceID, userID int64, via, kind string, details map[string]any)
	store *Store
	now   func() time.Time
	// maps follows Google Maps links one redirect at a time (maps.go).
	maps *http.Client
	// places finds a place's position by name when its link has none (geocode.go).
	places *geocoder
}

func NewHandler(st *Store) *Handler {
	return &Handler{store: st, now: time.Now, maps: &http.Client{CheckRedirect: noRedirects, Timeout: 6 * time.Second},
		places: &geocoder{base: nominatim, client: &http.Client{Timeout: 5 * time.Second}, gap: time.Second}}
}

// Register adds the routes; requireSpace is space.Handler.Require.
func (h *Handler) Register(mux *http.ServeMux, requireSpace func(http.Handler) http.Handler) {
	route := func(pattern string, fn httpx.HandlerFunc) { mux.Handle(pattern, requireSpace(fn)) }
	route("GET /api/restaurants", h.list)
	route("POST /api/restaurants", h.create)
	route("POST /api/restaurants/lookup", h.lookupHandler)
	route("GET /api/restaurants/{id}", h.get)
	route("PATCH /api/restaurants/{id}", h.update)
	route("DELETE /api/restaurants/{id}", h.delete)
	route("POST /api/restaurants/{id}/visits", h.addVisit)
	route("DELETE /api/restaurants/{id}/visits/{visit}", h.deleteVisit)
	route("PUT /api/restaurants/{id}/rating", h.rate)
}

var errNoSuch = httpx.Errorf(http.StatusNotFound, "no such restaurant")

func pathID(r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	return id, err == nil && id > 0
}

// log records a change to restaurant id, by name.
func (h *Handler) log(r *http.Request, kind string, id int64, details map[string]any) {
	if h.Log == nil {
		return
	}
	if details == nil {
		details = map[string]any{}
	}
	if _, ok := details["name"]; !ok {
		if cur, err := h.store.Get(r.Context(), space.SpaceFrom(r), account.UserFrom(r).ID, id); err == nil {
			details["name"] = cur.Name
		}
	}
	h.Log(r.Context(), space.SpaceFrom(r), account.UserFrom(r).ID, "app", kind, details)
}

// respond answers with the restaurant as it now is.
func (h *Handler) respond(w http.ResponseWriter, r *http.Request, status int, id int64) error {
	out, err := h.store.Get(r.Context(), space.SpaceFrom(r), account.UserFrom(r).ID, id)
	if errors.Is(err, db.ErrNotFound) {
		return errNoSuch
	}
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, status, out)
	return nil
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) error {
	list, err := h.store.List(r.Context(), space.SpaceFrom(r), account.UserFrom(r).ID)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, list)
	return nil
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	id, ok := pathID(r, "id")
	if !ok {
		return errNoSuch
	}
	return h.respond(w, r, http.StatusOK, id)
}

// Clean trims what was typed in and checks it; the error is an *httpx.Error
// whose message is for people.
func Clean(f Fields) (Fields, error) {
	f.Name, f.Area, f.Cuisine = strings.TrimSpace(f.Name), strings.TrimSpace(f.Area), strings.TrimSpace(f.Cuisine)
	f.MapURL, f.Notes = strings.TrimSpace(f.MapURL), strings.TrimSpace(f.Notes)
	switch {
	case f.Name == "" || utf8.RuneCountInString(f.Name) > maxName:
		return f, httpx.Errorf(http.StatusBadRequest, "a name must be 1 to %d characters", maxName)
	case utf8.RuneCountInString(f.Area) > maxArea:
		return f, httpx.Errorf(http.StatusBadRequest, "an area must be at most %d characters", maxArea)
	case utf8.RuneCountInString(f.Cuisine) > maxCuisine:
		return f, httpx.Errorf(http.StatusBadRequest, "a cuisine must be at most %d characters", maxCuisine)
	case utf8.RuneCountInString(f.Notes) > maxNotes:
		return f, httpx.Errorf(http.StatusBadRequest, "notes must be at most %d characters", maxNotes)
	}
	if f.MapURL != "" {
		// Only web links: the app puts this in an href.
		u, err := url.Parse(f.MapURL)
		if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || len(f.MapURL) > maxURL {
			return f, httpx.Errorf(http.StatusBadRequest, "a map link must be a web address starting with https://")
		}
	}
	return f, nil
}

// visitIn is a visit as it arrives: a date and an optional note.
type visitIn struct {
	VisitedOn string `json:"visited_on"`
	Note      string `json:"note"`
}

func (h *Handler) checkVisit(v visitIn) (visitIn, error) {
	var err error
	v.VisitedOn, v.Note, err = CheckVisit(v.VisitedOn, v.Note, h.now())
	return v, err
}

// CheckVisit checks a visit's date (YYYY-MM-DD, no later than today) and
// trims its note; the error is an *httpx.Error whose message is for people.
func CheckVisit(on, note string, now time.Time) (string, string, error) {
	note = strings.TrimSpace(note)
	d, err := time.Parse(time.DateOnly, on)
	// A day ahead of the server allows for any time zone.
	if err != nil || d.Year() < 1900 || d.After(now.AddDate(0, 0, 1)) {
		return on, note, httpx.Errorf(http.StatusBadRequest, "enter the date you went, no later than today")
	}
	if utf8.RuneCountInString(note) > maxNote {
		return on, note, httpx.Errorf(http.StatusBadRequest, "a visit's note must be at most %d characters", maxNote)
	}
	return on, note, nil
}

func (h *Handler) create(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Fields
		// Visit, if given, logs a first visit along with it.
		Visit *visitIn `json:"visit"`
		// Stars, if given (1 to 5), is the creator's rating.
		Stars int `json:"stars"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	f, err := Clean(in.Fields)
	if err != nil {
		return err
	}
	if in.Stars < 0 || in.Stars > 5 {
		return httpx.Errorf(http.StatusBadRequest, "a rating must be 1 to 5 stars")
	}
	var visit visitIn
	if in.Visit != nil {
		if visit, err = h.checkVisit(*in.Visit); err != nil {
			return err
		}
	}
	sp, me := space.SpaceFrom(r), account.UserFrom(r).ID
	id, err := h.store.Create(r.Context(), sp, me, f)
	if err != nil {
		return err
	}
	if in.Visit != nil {
		if err := h.store.AddVisit(r.Context(), sp, me, id, visit.VisitedOn, visit.Note); err != nil {
			return err
		}
	}
	if in.Stars > 0 {
		if err := h.store.Rate(r.Context(), sp, me, id, in.Stars); err != nil {
			return err
		}
	}
	h.log(r, "restaurant_added", id, map[string]any{"name": f.Name, "been": in.Visit != nil, "stars": in.Stars})
	return h.respond(w, r, http.StatusCreated, id)
}

func (h *Handler) update(w http.ResponseWriter, r *http.Request) error {
	id, ok := pathID(r, "id")
	if !ok {
		return errNoSuch
	}
	// Fields left out keep their value, so an older app that doesn't know a
	// newer field can't clear it.
	var in struct {
		Name    *string `json:"name"`
		Area    *string `json:"area"`
		Cuisine *string `json:"cuisine"`
		MapURL  *string `json:"map_url"`
		Notes   *string `json:"notes"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	cur, err := h.store.Get(r.Context(), space.SpaceFrom(r), account.UserFrom(r).ID, id)
	if errors.Is(err, db.ErrNotFound) {
		return errNoSuch
	}
	if err != nil {
		return err
	}
	f := Fields{Name: cur.Name, Area: cur.Area, Cuisine: cur.Cuisine, MapURL: cur.MapURL, Notes: cur.Notes}
	for _, p := range []struct {
		dst *string
		src *string
	}{{&f.Name, in.Name}, {&f.Area, in.Area}, {&f.Cuisine, in.Cuisine}, {&f.MapURL, in.MapURL}, {&f.Notes, in.Notes}} {
		if p.src != nil {
			*p.dst = *p.src
		}
	}
	if f, err = Clean(f); err != nil {
		return err
	}
	if err := h.store.Update(r.Context(), space.SpaceFrom(r), id, f); errors.Is(err, db.ErrNotFound) {
		return errNoSuch
	} else if err != nil {
		return err
	}
	h.log(r, "restaurant_edited", id, nil)
	return h.respond(w, r, http.StatusOK, id)
}

func (h *Handler) delete(w http.ResponseWriter, r *http.Request) error {
	id, ok := pathID(r, "id")
	if !ok {
		return errNoSuch
	}
	name := ""
	if cur, err := h.store.Get(r.Context(), space.SpaceFrom(r), account.UserFrom(r).ID, id); err == nil {
		name = cur.Name
	}
	if err := h.store.Delete(r.Context(), space.SpaceFrom(r), id); errors.Is(err, db.ErrNotFound) {
		return errNoSuch
	} else if err != nil {
		return err
	}
	h.log(r, "restaurant_deleted", id, map[string]any{"name": name})
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (h *Handler) addVisit(w http.ResponseWriter, r *http.Request) error {
	id, ok := pathID(r, "id")
	if !ok {
		return errNoSuch
	}
	var in visitIn
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	v, err := h.checkVisit(in)
	if err != nil {
		return err
	}
	err = h.store.AddVisit(r.Context(), space.SpaceFrom(r), account.UserFrom(r).ID, id, v.VisitedOn, v.Note)
	if errors.Is(err, db.ErrNotFound) {
		return errNoSuch
	} else if err != nil {
		return err
	}
	h.log(r, "visit_logged", id, map[string]any{"date": v.VisitedOn})
	return h.respond(w, r, http.StatusCreated, id)
}

func (h *Handler) deleteVisit(w http.ResponseWriter, r *http.Request) error {
	id, ok := pathID(r, "id")
	visit, ok2 := pathID(r, "visit")
	if !ok || !ok2 {
		return httpx.Errorf(http.StatusNotFound, "no such visit")
	}
	err := h.store.DeleteVisit(r.Context(), space.SpaceFrom(r), id, visit)
	if errors.Is(err, db.ErrNotFound) {
		return httpx.Errorf(http.StatusNotFound, "no such visit")
	} else if err != nil {
		return err
	}
	h.log(r, "visit_removed", id, nil)
	return h.respond(w, r, http.StatusOK, id)
}

func (h *Handler) rate(w http.ResponseWriter, r *http.Request) error {
	id, ok := pathID(r, "id")
	if !ok {
		return errNoSuch
	}
	var in struct {
		Stars int `json:"stars"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	if in.Stars < 0 || in.Stars > 5 {
		return httpx.Errorf(http.StatusBadRequest, "a rating must be 1 to 5 stars")
	}
	err := h.store.Rate(r.Context(), space.SpaceFrom(r), account.UserFrom(r).ID, id, in.Stars)
	if errors.Is(err, db.ErrNotFound) {
		return errNoSuch
	} else if err != nil {
		return err
	}
	h.log(r, "rated", id, map[string]any{"stars": in.Stars})
	return h.respond(w, r, http.StatusOK, id)
}
