// Package shift keeps one person's work shifts for the space: read from a
// photo of the monthly sheet (reader.go) or typed in, confirmed, and
// offered as a calendar feed both of you can subscribe to. The photo itself
// is never stored. The bus-stop alarm will read shift end times from here.
package shift

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/space"
)

// Settings say whose shifts these are and how to read the sheet.
type Settings struct {
	PersonName  string             `json:"person_name"`
	NameOnSheet string             `json:"name_on_sheet"`
	Lengths     map[string]float64 `json:"lengths"`
	TimeZone    string             `json:"time_zone"`
	// FeedPath is the calendar feed's address on this site, or "" while off.
	FeedPath string `json:"feed_path"`
	// Reader: photos can be read here (the server has an API key).
	Reader bool `json:"reader"`
}

// ---------- Store ----------

type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

type stored struct {
	Settings
	feedToken string
}

func (s *Store) settings(ctx context.Context, spaceID int64) (stored, error) {
	var st stored
	var lengths []byte
	var token sql.NullString
	err := s.db.QueryRowContext(ctx, `
		SELECT person_name, name_on_sheet, lengths, time_zone, feed_token FROM shift_settings WHERE space_id = $1`, spaceID).
		Scan(&st.PersonName, &st.NameOnSheet, &lengths, &st.TimeZone, &token)
	if errors.Is(err, sql.ErrNoRows) {
		st.TimeZone, err = "Asia/Tokyo", nil
	}
	if err != nil {
		return st, err
	}
	st.Lengths = DefaultLengths
	if len(lengths) > 0 {
		if err := json.Unmarshal(lengths, &st.Lengths); err != nil {
			return st, err
		}
	}
	st.feedToken = token.String
	if token.Valid {
		st.FeedPath = "/api/shifts/feed/" + token.String + ".ics"
	}
	return st, nil
}

func (s *Store) saveSettings(ctx context.Context, spaceID int64, st Settings) error {
	lengths, err := json.Marshal(st.Lengths)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `
		INSERT INTO shift_settings (space_id, person_name, name_on_sheet, lengths, time_zone) VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (space_id) DO UPDATE SET person_name = $2, name_on_sheet = $3, lengths = $4, time_zone = $5, updated_at = now()`,
		spaceID, st.PersonName, st.NameOnSheet, lengths, st.TimeZone)
	return err
}

// setFeedToken turns the feed on with a new secret, or off with "".
func (s *Store) setFeedToken(ctx context.Context, spaceID int64, token string) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO shift_settings (space_id, feed_token) VALUES ($1, NULLIF($2, ''))
		ON CONFLICT (space_id) DO UPDATE SET feed_token = NULLIF($2, ''), updated_at = now()`, spaceID, token)
	return err
}

func (s *Store) spaceByFeed(ctx context.Context, token string) (int64, error) {
	var id int64
	err := s.db.QueryRowContext(ctx, `SELECT space_id FROM shift_settings WHERE feed_token = $1`, token).Scan(&id)
	return id, err
}

// codes returns the codes from..to (inclusive), by date.
func (s *Store) codes(ctx context.Context, spaceID int64, from, to string) ([][2]string, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT to_char(day, 'YYYY-MM-DD'), code FROM shifts WHERE space_id = $1 AND day BETWEEN $2 AND $3 ORDER BY day`,
		spaceID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out [][2]string
	for rows.Next() {
		var d, c string
		if err := rows.Scan(&d, &c); err != nil {
			return nil, err
		}
		out = append(out, [2]string{d, c})
	}
	return out, rows.Err()
}

// replaceMonth makes a month's codes exactly days (date -> code).
func (s *Store) replaceMonth(ctx context.Context, spaceID int64, first, last string, days map[string]string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM shifts WHERE space_id = $1 AND day BETWEEN $2 AND $3`, spaceID, first, last); err != nil {
		return err
	}
	for date, code := range days {
		if _, err := tx.ExecContext(ctx, `INSERT INTO shifts (space_id, day, code) VALUES ($1, $2, $3)`, spaceID, date, code); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ---------- HTTP ----------

type Handler struct {
	store  *Store
	reader Reader // nil: photos can't be read on this server
	now    func() time.Time
}

func NewHandler(st *Store, reader Reader) *Handler {
	return &Handler{store: st, reader: reader, now: time.Now}
}

// Register adds the routes; requireSpace is space.Handler.Require. The feed
// needs no session: its secret address is the key, as calendar apps expect.
func (h *Handler) Register(mux *http.ServeMux, requireSpace func(http.Handler) http.Handler) {
	route := func(pattern string, fn httpx.HandlerFunc) { mux.Handle(pattern, requireSpace(fn)) }
	route("GET /api/shifts", h.month)
	route("PUT /api/shifts/month", h.saveMonth)
	route("POST /api/shifts/read", h.read)
	route("GET /api/shifts/settings", h.getSettings)
	route("PUT /api/shifts/settings", h.putSettings)
	route("POST /api/shifts/feed", h.turnOnFeed)
	route("DELETE /api/shifts/feed", h.turnOffFeed)
	mux.Handle("GET /api/shifts/feed/{file}", httpx.HandlerFunc(h.feed))
}

var monthPattern = regexp.MustCompile(`^[0-9]{4}-(0[1-9]|1[0-2])$`)

// monthRange is a month's first and last dates, for "YYYY-MM".
func monthRange(month string) (first, last time.Time, ok bool) {
	if !monthPattern.MatchString(month) {
		return
	}
	first, err := time.Parse("2006-01", month)
	if err != nil {
		return
	}
	return first, first.AddDate(0, 1, -1), true
}

func (h *Handler) loadSettings(r *http.Request) (stored, *time.Location, error) {
	st, err := h.store.settings(r.Context(), space.SpaceFrom(r))
	if err != nil {
		return st, nil, err
	}
	zone, err := time.LoadLocation(st.TimeZone)
	if err != nil {
		zone = time.UTC
	}
	st.Reader = h.reader != nil
	return st, zone, nil
}

// MonthView is a month of shifts as the app shows it.
type MonthView struct {
	Month    string   `json:"month"`
	Settings Settings `json:"settings"`
	Days     []Day    `json:"days"`
}

func (h *Handler) month(w http.ResponseWriter, r *http.Request) error {
	view, err := h.store.Month(r.Context(), space.SpaceFrom(r), r.URL.Query().Get("month"), h.now())
	var inv *InvalidError
	if errors.As(err, &inv) {
		return httpx.Errorf(http.StatusBadRequest, "%s", inv.Msg)
	}
	if err != nil {
		return err
	}
	view.Settings.Reader = h.reader != nil
	httpx.WriteJSON(w, http.StatusOK, view)
	return nil
}

const maxCode = 12

// InvalidError is a request that breaks a rule; Msg says which, for people.
type InvalidError struct{ Msg string }

func (e *InvalidError) Error() string { return e.Msg }

func invalidf(format string, args ...any) error { return &InvalidError{fmt.Sprintf(format, args...)} }

// DayCode is a day and its code, as typed or read.
type DayCode struct {
	Date string `json:"date"`
	Code string `json:"code"`
}

// Settings returns the space's shift settings and their time zone.
func (s *Store) Settings(ctx context.Context, spaceID int64) (Settings, *time.Location, error) {
	st, err := s.settings(ctx, spaceID)
	if err != nil {
		return Settings{}, nil, err
	}
	zone, err := time.LoadLocation(st.TimeZone)
	if err != nil {
		zone = time.UTC
	}
	return st.Settings, zone, nil
}

// Month returns a month of shifts ("YYYY-MM"; "" is the month it is now).
func (s *Store) Month(ctx context.Context, spaceID int64, month string, now time.Time) (MonthView, error) {
	st, zone, err := s.Settings(ctx, spaceID)
	if err != nil {
		return MonthView{}, err
	}
	if month == "" {
		month = now.In(zone).Format("2006-01")
	}
	first, last, ok := monthRange(month)
	if !ok {
		return MonthView{}, invalidf("month must be YYYY-MM")
	}
	codes, err := s.codes(ctx, spaceID, first.Format(time.DateOnly), last.Format(time.DateOnly))
	if err != nil {
		return MonthView{}, err
	}
	view := MonthView{Month: month, Settings: st, Days: []Day{}}
	for _, c := range codes {
		view.Days = append(view.Days, Explain(c[0], c[1], st.Lengths, zone))
	}
	return view, nil
}

// SaveMonth makes a month's days exactly days (an empty code clears a day),
// and returns the month as saved.
func (s *Store) SaveMonth(ctx context.Context, spaceID int64, month string, days []DayCode) (MonthView, error) {
	first, last, ok := monthRange(month)
	if !ok {
		return MonthView{}, invalidf("month must be YYYY-MM")
	}
	codes := map[string]string{}
	for _, d := range days {
		date, err := time.Parse(time.DateOnly, d.Date)
		if err != nil || date.Before(first) || date.After(last) {
			return MonthView{}, invalidf("every day must be in %s", month)
		}
		code := normalize(d.Code)
		if utf8.RuneCountInString(code) > maxCode {
			return MonthView{}, invalidf("a shift code must be at most %d characters", maxCode)
		}
		if code != "" {
			codes[d.Date] = code
		}
	}
	if err := s.replaceMonth(ctx, spaceID, first.Format(time.DateOnly), last.Format(time.DateOnly), codes); err != nil {
		return MonthView{}, err
	}
	return s.Month(ctx, spaceID, month, time.Time{})
}

func (h *Handler) saveMonth(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Month string    `json:"month"`
		Days  []DayCode `json:"days"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	view, err := h.store.SaveMonth(r.Context(), space.SpaceFrom(r), in.Month, in.Days)
	var inv *InvalidError
	if errors.As(err, &inv) {
		return httpx.Errorf(http.StatusBadRequest, "%s", inv.Msg)
	}
	if err != nil {
		return err
	}
	view.Settings.Reader = h.reader != nil
	httpx.WriteJSON(w, http.StatusOK, view)
	return nil
}

// maxPhoto is the largest photo accepted. The app shrinks photos before
// sending them, so this is generous.
const maxPhoto = 8 << 20

// read reads a photo of the sheet and answers with the month it found, for
// the person to check before saving it (saveMonth). Nothing is stored.
func (h *Handler) read(w http.ResponseWriter, r *http.Request) error {
	if h.reader == nil {
		return httpx.Errorf(http.StatusServiceUnavailable, "reading photos isn't set up on this server; enter the shifts by hand")
	}
	st, zone, err := h.loadSettings(r)
	if err != nil {
		return err
	}
	if st.NameOnSheet == "" {
		return httpx.Errorf(http.StatusBadRequest, "first set the name as it's printed on the sheet")
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxPhoto+1<<20)
	file, _, err := r.FormFile("photo")
	if err != nil {
		return httpx.Errorf(http.StatusBadRequest, "send a photo of the sheet (JPEG, PNG or WebP, up to 8 MB)")
	}
	defer file.Close()
	image, err := io.ReadAll(io.LimitReader(file, maxPhoto+1))
	if err != nil || len(image) > maxPhoto {
		return httpx.Errorf(http.StatusBadRequest, "send a photo of the sheet (JPEG, PNG or WebP, up to 8 MB)")
	}
	mediaType := http.DetectContentType(image)
	switch mediaType {
	case "image/jpeg", "image/png", "image/webp":
	default:
		return httpx.Errorf(http.StatusBadRequest, "send a photo of the sheet (JPEG, PNG or WebP, up to 8 MB)")
	}
	// The month after next week: a sheet for next month is usually shared
	// late in the month before.
	soon := h.now().In(zone).AddDate(0, 0, 7)
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Minute)
	defer cancel()
	sheet, err := h.reader.Read(ctx, image, mediaType, st.NameOnSheet, Month{soon.Year(), int(soon.Month())})
	if errors.Is(err, ErrRefused) {
		return httpx.Errorf(http.StatusUnprocessableEntity, "the photo couldn't be read; try a clearer one, or enter the shifts by hand")
	}
	if err != nil {
		slog.ErrorContext(r.Context(), "read shift sheet", "err", err)
		return httpx.Errorf(http.StatusBadGateway, "the photo couldn't be read just now; try again, or enter the shifts by hand")
	}
	if !sheet.Found {
		return httpx.Errorf(http.StatusUnprocessableEntity, "couldn't find %s on the sheet; check the name in settings", st.NameOnSheet)
	}
	month := fmt.Sprintf("%04d-%02d", sheet.Year, sheet.Month)
	first, last, ok := monthRange(month)
	if !ok {
		return httpx.Errorf(http.StatusUnprocessableEntity, "couldn't tell which month the sheet is for")
	}
	view := struct {
		MonthView
		NameAsRead string `json:"name_as_read"`
		Notes      string `json:"notes"`
	}{MonthView: MonthView{Month: month, Settings: st.Settings, Days: []Day{}}, NameAsRead: sheet.NameAsRead, Notes: sheet.Notes}
	for _, d := range sheet.Days {
		date := first.AddDate(0, 0, d.Day-1)
		if d.Day < 1 || date.After(last) || normalize(d.Code) == "" {
			continue
		}
		view.Days = append(view.Days, Explain(date.Format(time.DateOnly), d.Code, st.Lengths, zone))
	}
	httpx.WriteJSON(w, http.StatusOK, view)
	return nil
}

func (h *Handler) getSettings(w http.ResponseWriter, r *http.Request) error {
	st, _, err := h.loadSettings(r)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, st.Settings)
	return nil
}

var letter = regexp.MustCompile(`^[A-Z]$`)

func (h *Handler) putSettings(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		PersonName  *string             `json:"person_name"`
		NameOnSheet *string             `json:"name_on_sheet"`
		Lengths     *map[string]float64 `json:"lengths"`
		TimeZone    *string             `json:"time_zone"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	cur, _, err := h.loadSettings(r)
	if err != nil {
		return err
	}
	st := cur.Settings
	if in.PersonName != nil {
		st.PersonName = strings.TrimSpace(*in.PersonName)
	}
	if in.NameOnSheet != nil {
		st.NameOnSheet = strings.TrimSpace(*in.NameOnSheet)
	}
	if utf8.RuneCountInString(st.PersonName) > 40 || utf8.RuneCountInString(st.NameOnSheet) > 80 {
		return httpx.Errorf(http.StatusBadRequest, "a name must be at most 40 characters, and the name on the sheet 80")
	}
	if in.Lengths != nil {
		st.Lengths = map[string]float64{}
		for k, v := range *in.Lengths {
			k = normalize(k)
			if !letter.MatchString(k) || v <= 0 || v > 24 {
				return httpx.Errorf(http.StatusBadRequest, "each shift letter needs a length of more than 0 and at most 24 hours")
			}
			st.Lengths[k] = v
		}
	}
	if in.TimeZone != nil {
		if _, err := time.LoadLocation(*in.TimeZone); err != nil || *in.TimeZone == "" {
			return httpx.Errorf(http.StatusBadRequest, "unknown time zone")
		}
		st.TimeZone = *in.TimeZone
	}
	if err := h.store.saveSettings(r.Context(), space.SpaceFrom(r), st); err != nil {
		return err
	}
	return h.getSettings(w, r)
}

func (h *Handler) turnOnFeed(w http.ResponseWriter, r *http.Request) error {
	raw := make([]byte, 24)
	if _, err := rand.Read(raw); err != nil {
		return err
	}
	if err := h.store.setFeedToken(r.Context(), space.SpaceFrom(r), base64.RawURLEncoding.EncodeToString(raw)); err != nil {
		return err
	}
	return h.getSettings(w, r)
}

func (h *Handler) turnOffFeed(w http.ResponseWriter, r *http.Request) error {
	if err := h.store.setFeedToken(r.Context(), space.SpaceFrom(r), ""); err != nil {
		return err
	}
	return h.getSettings(w, r)
}

// feed is the calendar (iCalendar) that Google Calendar and others
// subscribe to: shifts from two months back to a year ahead.
func (h *Handler) feed(w http.ResponseWriter, r *http.Request) error {
	token, ok := strings.CutSuffix(r.PathValue("file"), ".ics")
	if !ok || len(token) < 20 {
		return httpx.Errorf(http.StatusNotFound, "not found")
	}
	spaceID, err := h.store.spaceByFeed(r.Context(), token)
	if errors.Is(err, sql.ErrNoRows) {
		return httpx.Errorf(http.StatusNotFound, "not found")
	}
	if err != nil {
		return err
	}
	st, err := h.store.settings(r.Context(), spaceID)
	if err != nil {
		return err
	}
	zone, err := time.LoadLocation(st.TimeZone)
	if err != nil {
		zone = time.UTC
	}
	now := h.now().In(zone)
	codes, err := h.store.codes(r.Context(), spaceID, now.AddDate(0, -2, 0).Format(time.DateOnly), now.AddDate(1, 0, 0).Format(time.DateOnly))
	if err != nil {
		return err
	}
	days := make([]Day, 0, len(codes))
	for _, c := range codes {
		days = append(days, Explain(c[0], c[1], st.Lengths, zone))
	}
	w.Header().Set("Content-Type", "text/calendar; charset=utf-8")
	w.Header().Set("Content-Disposition", `inline; filename="shifts.ics"`)
	_, err = io.WriteString(w, ICS(days, st.PersonName, now))
	return err
}
