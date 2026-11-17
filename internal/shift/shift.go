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
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/lib/pq"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/space"
)

// Settings say whose shifts these are and how to read the sheet.
type Settings struct {
	PersonName  string             `json:"person_name"`
	NameOnSheet string             `json:"name_on_sheet"`
	Lengths     map[string]float64 `json:"lengths"`
	TimeZone    string             `json:"time_zone"`
	// ClosingDays are the days of the month the hours are sent in (締め申請通知),
	// marked on the calendar. A day past the month's end means its last day.
	ClosingDays []int `json:"closing_days"`
	// FeedPath is the calendar feed's address on this site, or "" while off.
	FeedPath string `json:"feed_path"`
	// Reader: photos can be read here (the server has an API key).
	Reader bool `json:"reader"`
}

// ---------- Store ----------

type Store struct {
	db *sql.DB
}

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

type stored struct {
	Settings
	feedToken string
}

func (s *Store) settings(ctx context.Context, spaceID int64) (stored, error) {
	var st stored
	var lengths []byte
	var token sql.NullString
	var closing []int64
	err := s.db.QueryRowContext(ctx, `
		SELECT person_name, name_on_sheet, lengths, time_zone, feed_token, closing_days FROM shift_settings WHERE space_id = $1`, spaceID).
		Scan(&st.PersonName, &st.NameOnSheet, &lengths, &st.TimeZone, &token, (*pq.Int64Array)(&closing))
	if errors.Is(err, sql.ErrNoRows) {
		st.TimeZone, closing, err = "Asia/Tokyo", []int64{15, 31}, nil
	}
	st.ClosingDays = make([]int, len(closing))
	for i, d := range closing {
		st.ClosingDays[i] = int(d)
	}
	if err != nil {
		return st, err
	}
	// A copy: saved lengths are read on top, and must never change the defaults.
	st.Lengths = map[string]float64{}
	for k, v := range DefaultLengths {
		st.Lengths[k] = v
	}
	if len(lengths) > 0 {
		saved := map[string]float64{}
		if err := json.Unmarshal(lengths, &saved); err != nil {
			return st, err
		}
		st.Lengths = saved
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
		INSERT INTO shift_settings (space_id, person_name, name_on_sheet, lengths, time_zone, closing_days) VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (space_id) DO UPDATE SET person_name = $2, name_on_sheet = $3, lengths = $4, time_zone = $5, closing_days = $6, updated_at = now()`,
		spaceID, st.PersonName, st.NameOnSheet, lengths, st.TimeZone, pq.Array(st.ClosingDays))
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

// codes returns the days from..to (inclusive), by date.
func (s *Store) codes(ctx context.Context, spaceID int64, from, to string) ([]DayCode, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT to_char(day, 'YYYY-MM-DD'), code, coworkers FROM shifts WHERE space_id = $1 AND day BETWEEN $2 AND $3 ORDER BY day`,
		spaceID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DayCode
	for rows.Next() {
		var d DayCode
		if err := rows.Scan(&d.Date, &d.Code, (*pq.StringArray)(&d.With)); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// explain works out a stored day, with who's on the same shift.
func explain(d DayCode, lengths map[string]float64, zone *time.Location) Day {
	day := Explain(d.Date, d.Code, lengths, zone)
	if day.Kind == Work && len(d.With) > 0 {
		day.With = d.With
	}
	return day
}

// replaceMonth makes a month's days exactly days (by date).
func (s *Store) replaceMonth(ctx context.Context, spaceID int64, first, last string, days map[string]DayCode) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM shifts WHERE space_id = $1 AND day BETWEEN $2 AND $3`, spaceID, first, last); err != nil {
		return err
	}
	for date, d := range days {
		if _, err := tx.ExecContext(ctx, `INSERT INTO shifts (space_id, day, code, coworkers) VALUES ($1, $2, $3, $4)`,
			spaceID, date, d.Code, pq.StringArray(append([]string{}, d.With...))); err != nil { // never NULL
			return err
		}
	}
	return tx.Commit()
}

// closingMonths returns the months from..to ("YYYY-MM-01", inclusive) whose
// 締め申請通知 days differ from the usual ones, by month ("YYYY-MM").
func (s *Store) closingMonths(ctx context.Context, spaceID int64, from, to string) (map[string][]int, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT to_char(month, 'YYYY-MM'), days FROM shift_closing_months WHERE space_id = $1 AND month BETWEEN $2 AND $3`,
		spaceID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string][]int{}
	for rows.Next() {
		var month string
		var days []int64
		if err := rows.Scan(&month, (*pq.Int64Array)(&days)); err != nil {
			return nil, err
		}
		out[month] = make([]int, len(days))
		for i, d := range days {
			out[month][i] = int(d)
		}
	}
	return out, rows.Err()
}

// setClosingMonth makes a month's 締め申請通知 days days, or the usual ones
// again with nil.
func (s *Store) setClosingMonth(ctx context.Context, spaceID int64, first string, days []int) error {
	if days == nil {
		_, err := s.db.ExecContext(ctx, `DELETE FROM shift_closing_months WHERE space_id = $1 AND month = $2`, spaceID, first)
		return err
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO shift_closing_months (space_id, month, days) VALUES ($1, $2, $3)
		ON CONFLICT (space_id, month) DO UPDATE SET days = $3, updated_at = now()`,
		spaceID, first, pq.Array(days))
	return err
}

// MonthClosing is the usual closing days as they fall in a month: dates in
// it (a day past its end is its last day), each once, in order.
func MonthClosing(usual []int, first time.Time) []int {
	last := first.AddDate(0, 1, -1).Day()
	out := []int{}
	seen := map[int]bool{}
	for _, d := range usual {
		d = min(d, last)
		if d >= 1 && !seen[d] {
			seen[d] = true
			out = append(out, d)
		}
	}
	sort.Ints(out)
	return out
}

// ---------- HTTP ----------

type Handler struct {
	// Log, if set, records changes for the Us tab's history (internal/activity).
	Log    func(ctx context.Context, spaceID, userID int64, via, kind string, details map[string]any)
	store  *Store
	reader Reader // nil: photos can't be read on this server
	now    func() time.Time
	reads  reads
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
	route("POST /api/shifts/reads", h.startRead)
	route("GET /api/shifts/reads/{id}", h.readStatus)
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

func (h *Handler) log(r *http.Request, kind string, details map[string]any) {
	if h.Log != nil {
		h.Log(r.Context(), space.SpaceFrom(r), account.UserFrom(r).ID, "app", kind, details)
	}
}

// SettingsChanges describes what changed between two settings, for the
// history: {"person_name": [old, new], "lengths": {"F": [8, 9]}, …}.
func SettingsChanges(before, after Settings) map[string]any {
	d := map[string]any{}
	if before.PersonName != after.PersonName {
		d["person_name"] = []string{before.PersonName, after.PersonName}
	}
	if before.NameOnSheet != after.NameOnSheet {
		d["name_on_sheet"] = []string{before.NameOnSheet, after.NameOnSheet}
	}
	if before.TimeZone != after.TimeZone {
		d["time_zone"] = []string{before.TimeZone, after.TimeZone}
	}
	if b, a := daysText(before.ClosingDays), daysText(after.ClosingDays); b != a {
		d["closing_days"] = []string{b, a}
	}
	lengths := map[string]any{}
	for k, v := range after.Lengths {
		if old, ok := before.Lengths[k]; !ok || old != v {
			lengths[k] = []any{before.Lengths[k], v}
		}
	}
	for k, old := range before.Lengths {
		if _, ok := after.Lengths[k]; !ok {
			lengths[k] = []any{old, nil}
		}
	}
	if len(lengths) > 0 {
		d["lengths"] = lengths
	}
	return d
}

// MonthView is a month of shifts as the app shows it.
type MonthView struct {
	Month    string   `json:"month"`
	Settings Settings `json:"settings"`
	Days     []Day    `json:"days"`
	// ClosingDays are this month's 締め申請通知 days, as dates in the month;
	// ClosingCustom says they were set for this month only, not the usual
	// days from the settings.
	ClosingDays   []int `json:"closing_days"`
	ClosingCustom bool  `json:"closing_custom"`
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

// DayCode is a day and its code, as typed or read, and who else is on the
// same shift (optional).
type DayCode struct {
	Date string   `json:"date"`
	Code string   `json:"code"`
	With []string `json:"with,omitempty"`
}

// Limits on the coworkers kept for a day.
const (
	maxWith     = 30
	maxWithName = 40
)

// cleanNames tidies names as read or typed: trimmed, no blanks or repeats.
func cleanNames(names []string) ([]string, error) {
	var out []string
	seen := map[string]bool{}
	for _, n := range names {
		n = strings.Join(strings.Fields(n), " ")
		if n == "" || seen[n] {
			continue
		}
		if utf8.RuneCountInString(n) > maxWithName {
			return nil, invalidf("a coworker's name must be at most %d characters", maxWithName)
		}
		seen[n] = true
		out = append(out, n)
	}
	if len(out) > maxWith {
		return nil, invalidf("at most %d coworkers a day", maxWith)
	}
	return out, nil
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

// SetLengths changes the hours for the letters given; others stay.
func (s *Store) SetLengths(ctx context.Context, spaceID int64, lengths map[string]float64) (Settings, error) {
	st, err := s.settings(ctx, spaceID)
	if err != nil {
		return Settings{}, err
	}
	next := map[string]float64{}
	for k, v := range st.Lengths {
		next[k] = v
	}
	for k, v := range lengths {
		k = normalize(k)
		if !letter.MatchString(k) || v <= 0 || v > 24 {
			return Settings{}, invalidf("each shift letter needs a length of more than 0 and at most 24 hours")
		}
		next[k] = v
	}
	st.Lengths = next
	if err := s.saveSettings(ctx, spaceID, st.Settings); err != nil {
		return Settings{}, err
	}
	return st.Settings, nil
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
	custom, err := s.closingMonths(ctx, spaceID, first.Format(time.DateOnly), first.Format(time.DateOnly))
	if err != nil {
		return MonthView{}, err
	}
	view := MonthView{Month: month, Settings: st, Days: []Day{}, ClosingDays: MonthClosing(st.ClosingDays, first)}
	if days, ok := custom[month]; ok {
		view.ClosingDays, view.ClosingCustom = days, true
	}
	for _, c := range codes {
		view.Days = append(view.Days, explain(c, st.Lengths, zone))
	}
	return view, nil
}

// SaveMonth makes a month's days exactly days (an empty code clears a day),
// and returns the month as saved. closing, if not nil, are the month's
// 締め申請通知 days (dates in it); the usual days make it follow the settings
// again.
func (s *Store) SaveMonth(ctx context.Context, spaceID int64, month string, days []DayCode, closing *[]int) (MonthView, error) {
	first, last, ok := monthRange(month)
	if !ok {
		return MonthView{}, invalidf("month must be YYYY-MM")
	}
	codes := map[string]DayCode{}
	for _, d := range days {
		date, err := time.Parse(time.DateOnly, d.Date)
		if err != nil || date.Before(first) || date.After(last) {
			return MonthView{}, invalidf("every day must be in %s", month)
		}
		code := normalize(d.Code)
		if utf8.RuneCountInString(code) > maxCode {
			return MonthView{}, invalidf("a shift code must be at most %d characters", maxCode)
		}
		with, err := cleanNames(d.With)
		if err != nil {
			return MonthView{}, err
		}
		if code != "" {
			codes[d.Date] = DayCode{Date: d.Date, Code: code, With: with}
		}
	}
	var closingDays []int
	if closing != nil {
		closingDays = []int{}
		seen := map[int]bool{}
		for _, d := range *closing {
			if d < 1 || d > last.Day() {
				return MonthView{}, invalidf("a closing day must be a day in %s", month)
			}
			if !seen[d] {
				seen[d] = true
				closingDays = append(closingDays, d)
			}
		}
		if len(closingDays) > maxClosing {
			return MonthView{}, invalidf("at most %d closing days", maxClosing)
		}
		sort.Ints(closingDays)
	}
	if err := s.replaceMonth(ctx, spaceID, first.Format(time.DateOnly), last.Format(time.DateOnly), codes); err != nil {
		return MonthView{}, err
	}
	if closing != nil {
		st, err := s.settings(ctx, spaceID)
		if err != nil {
			return MonthView{}, err
		}
		if daysText(closingDays) == daysText(MonthClosing(st.ClosingDays, first)) {
			closingDays = nil // the usual days: follow the settings again
		}
		if err := s.setClosingMonth(ctx, spaceID, first.Format(time.DateOnly), closingDays); err != nil {
			return MonthView{}, err
		}
	}
	return s.Month(ctx, spaceID, month, time.Time{})
}

func (h *Handler) saveMonth(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Month string    `json:"month"`
		Days  []DayCode `json:"days"`
		// Corrections are names the photo reader got wrong, as fixed while
		// checking a scan; later scans use them.
		Corrections []NameFix `json:"corrections"`
		// ClosingDays, if sent, are this month's 締め申請通知 days (dates in
		// the month); the usual days go back to following the settings.
		ClosingDays *[]int `json:"closing_days"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	fixes, err := cleanFixes(in.Corrections)
	var inv *InvalidError
	if errors.As(err, &inv) {
		return httpx.Errorf(http.StatusBadRequest, "%s", inv.Msg)
	}
	var before MonthView
	if in.ClosingDays != nil {
		before, _ = h.store.Month(r.Context(), space.SpaceFrom(r), in.Month, h.now()) // for the history only
	}
	view, err := h.store.SaveMonth(r.Context(), space.SpaceFrom(r), in.Month, in.Days, in.ClosingDays)
	if errors.As(err, &inv) {
		return httpx.Errorf(http.StatusBadRequest, "%s", inv.Msg)
	}
	if err != nil {
		return err
	}
	if err := h.store.saveNameFixes(r.Context(), space.SpaceFrom(r), fixes); err != nil {
		slog.ErrorContext(r.Context(), "save name corrections", "err", err) // the month is saved; don't fail it
	}
	details := map[string]any{"month": view.Month, "days": len(view.Days)}
	if in.ClosingDays != nil && daysText(before.ClosingDays) != daysText(view.ClosingDays) {
		details["closing_days"] = []string{daysText(before.ClosingDays), daysText(view.ClosingDays)}
	}
	h.log(r, "shifts_saved", details)
	view.Settings.Reader = h.reader != nil
	httpx.WriteJSON(w, http.StatusOK, view)
	return nil
}

// maxPhoto is the largest photo accepted. The app shrinks photos before
// sending them, so this is generous.
const maxPhoto = 8 << 20

// read reads a photo of the sheet and answers with the month it found, for
// the person to check before saving it (saveMonth). Nothing is stored.
// Reading usually takes longer than a request may run behind CloudFront, so
// the apps start a read and ask after it instead (reads.go); this stays for
// app versions already installed.
func (h *Handler) read(w http.ResponseWriter, r *http.Request) error {
	run, err := h.prepareRead(w, r)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(r.Context(), readTimeout)
	defer cancel()
	view, err := run(ctx)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, view)
	return nil
}

// readTimeout is how long Claude gets to read a sheet.
const readTimeout = 3 * time.Minute

// uploadTimeout is how long a photo of the sheet may take to arrive.
const uploadTimeout = 3 * time.Minute

// prepareRead checks a request to read a photo and takes the photo from it.
// The read itself is the returned func: it answers with the month found, or
// an *httpx.Error to show.
func (h *Handler) prepareRead(w http.ResponseWriter, r *http.Request) (func(context.Context) (any, error), error) {
	if h.reader == nil {
		return nil, httpx.Errorf(http.StatusServiceUnavailable, "reading photos isn't set up on this server; enter the shifts by hand")
	}
	st, zone, err := h.loadSettings(r)
	if err != nil {
		return nil, err
	}
	if st.NameOnSheet == "" {
		return nil, httpx.Errorf(http.StatusBadRequest, "first set the name as it's printed on the sheet")
	}
	// A photo takes longer to send than the server lets a request take (30 s,
	// cmd/server) on a phone's slow upload: a few MB over mobile data. Cut off,
	// the app only hears that the server couldn't be reached. So this request
	// gets uploadTimeout to arrive, and the read after it (POST /api/shifts/read) its own time.
	rc := http.NewResponseController(w)
	_ = rc.SetReadDeadline(time.Now().Add(uploadTimeout))
	_ = rc.SetWriteDeadline(time.Now().Add(uploadTimeout + readTimeout))
	r.Body = http.MaxBytesReader(w, r.Body, maxPhoto+1<<20)
	file, _, err := r.FormFile("photo")
	if err != nil {
		return nil, httpx.Errorf(http.StatusBadRequest, "send a photo of the sheet (JPEG, PNG or WebP, up to 8 MB)")
	}
	defer file.Close()
	image, err := io.ReadAll(io.LimitReader(file, maxPhoto+1))
	if err != nil || len(image) > maxPhoto {
		return nil, httpx.Errorf(http.StatusBadRequest, "send a photo of the sheet (JPEG, PNG or WebP, up to 8 MB)")
	}
	mediaType := http.DetectContentType(image)
	switch mediaType {
	case "image/jpeg", "image/png", "image/webp":
	default:
		return nil, httpx.Errorf(http.StatusBadRequest, "send a photo of the sheet (JPEG, PNG or WebP, up to 8 MB)")
	}
	// The month after next week: a sheet for next month is usually shared
	// late in the month before.
	soon := h.now().In(zone).AddDate(0, 0, 7)
	hint := Month{Year: soon.Year(), Month: int(soon.Month())}
	// Names this space has had before: told to the reader, and matched to
	// whatever it still gets a character wrong. Missing them is no reason not to read.
	spaceID := space.SpaceFrom(r)
	known, err := h.store.knownNames(r.Context(), spaceID)
	if err != nil {
		slog.ErrorContext(r.Context(), "load known names", "err", err)
	}
	fixes, err := h.store.nameFixes(r.Context(), spaceID)
	if err != nil {
		slog.ErrorContext(r.Context(), "load name corrections", "err", err)
	}
	hint.Names = known
	return func(ctx context.Context) (any, error) {
		sheet, err := h.reader.Read(ctx, image, mediaType, st.NameOnSheet, hint)
		if errors.Is(err, ErrRefused) {
			return nil, httpx.Errorf(http.StatusUnprocessableEntity, "the photo couldn't be read; try a clearer one, or enter the shifts by hand")
		}
		if err != nil {
			slog.ErrorContext(ctx, "read shift sheet", "err", err)
			return nil, httpx.Errorf(http.StatusBadGateway, "the photo couldn't be read just now; try again, or enter the shifts by hand")
		}
		if changes := applyNames(&sheet, fixes, known); len(changes) > 0 {
			sheet.Notes = strings.TrimSpace(sheet.Notes + "\nNames changed to match people you've had before: " + strings.Join(changes, ", "))
		}
		return sheetView(sheet, st, zone)
	}, nil
}

// sheetView is the month read from a sheet, as the person will check it.
func sheetView(sheet Sheet, st stored, zone *time.Location) (any, error) {
	if !sheet.Found {
		return nil, httpx.Errorf(http.StatusUnprocessableEntity, "couldn't find %s on the sheet; check the name in settings", st.NameOnSheet)
	}
	month := fmt.Sprintf("%04d-%02d", sheet.Year, sheet.Month)
	first, last, ok := monthRange(month)
	if !ok {
		return nil, httpx.Errorf(http.StatusUnprocessableEntity, "couldn't tell which month the sheet is for")
	}
	view := struct {
		MonthView
		NameAsRead string `json:"name_as_read"`
		Notes      string `json:"notes"`
	}{MonthView: MonthView{Month: month, Settings: st.Settings, Days: []Day{}}, NameAsRead: sheet.NameAsRead, Notes: sheet.Notes}
	var leave []string // days whose shift was taken as leave
	for _, d := range sheet.Days {
		date := first.AddDate(0, 0, d.Day-1)
		if d.Day < 1 || date.After(last) || normalize(d.Code) == "" {
			continue
		}
		code := CellCode(d.Code, d.Note)
		if code != d.Code {
			leave = append(leave, fmt.Sprintf("%d (%s)", d.Day, normalize(d.Code)))
		}
		day := Explain(date.Format(time.DateOnly), code, st.Lengths, zone)
		if with, err := cleanNames(d.With); err == nil && day.Kind == Work && code == d.Code {
			day.With = with
		}
		view.Days = append(view.Days, day)
	}
	if len(leave) > 0 {
		line := "有休 under the code, so paid leave: " + strings.Join(leave, ", ")
		view.Notes = strings.TrimSpace(view.Notes + "\n" + line)
	}
	return view, nil
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

// daysText writes closing days as the history shows them: "15, 31".
func daysText(days []int) string {
	parts := make([]string, len(days))
	for i, d := range days {
		parts[i] = strconv.Itoa(d)
	}
	return strings.Join(parts, ", ")
}

// maxClosing is the most closing days in a month.
const maxClosing = 10

// closingDays checks and tidies closing days: 1 to 31, each once, in order.
func closingDays(in []int) ([]int, error) {
	seen := map[int]bool{}
	out := []int{}
	for _, d := range in {
		if d < 1 || d > 31 {
			return nil, httpx.Errorf(http.StatusBadRequest, "a closing day must be from 1 to 31 (31 is the last day of any month)")
		}
		if !seen[d] {
			seen[d] = true
			out = append(out, d)
		}
	}
	if len(out) > maxClosing {
		return nil, httpx.Errorf(http.StatusBadRequest, "at most 10 closing days")
	}
	sort.Ints(out)
	return out, nil
}

func (h *Handler) putSettings(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		PersonName  *string             `json:"person_name"`
		NameOnSheet *string             `json:"name_on_sheet"`
		Lengths     *map[string]float64 `json:"lengths"`
		TimeZone    *string             `json:"time_zone"`
		ClosingDays *[]int              `json:"closing_days"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	cur, _, err := h.loadSettings(r)
	if err != nil {
		return err
	}
	st := cur.Settings
	before := st
	before.Lengths = map[string]float64{}
	for k, v := range cur.Lengths {
		before.Lengths[k] = v
	}
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
	if in.ClosingDays != nil {
		if st.ClosingDays, err = closingDays(*in.ClosingDays); err != nil {
			return err
		}
	}
	if err := h.store.saveSettings(r.Context(), space.SpaceFrom(r), st); err != nil {
		return err
	}
	if d := SettingsChanges(before, st); len(d) > 0 {
		h.log(r, "shift_settings", d)
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
	h.log(r, "calendar_link_new", nil)
	return h.getSettings(w, r)
}

func (h *Handler) turnOffFeed(w http.ResponseWriter, r *http.Request) error {
	if err := h.store.setFeedToken(r.Context(), space.SpaceFrom(r), ""); err != nil {
		return err
	}
	h.log(r, "calendar_link_off", nil)
	return h.getSettings(w, r)
}

// feed is the calendar (iCalendar) that Google Calendar and others
// subscribe to: shifts and 締め申請通知 days from two months back to a year ahead.
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
	from, to := now.AddDate(0, -2, 0), now.AddDate(1, 0, 0)
	codes, err := h.store.codes(r.Context(), spaceID, from.Format(time.DateOnly), to.Format(time.DateOnly))
	if err != nil {
		return err
	}
	days := make([]Day, 0, len(codes))
	for _, c := range codes {
		days = append(days, explain(c, st.Lengths, zone))
	}
	custom, err := h.store.closingMonths(r.Context(), spaceID,
		time.Date(from.Year(), from.Month(), 1, 0, 0, 0, 0, time.UTC).Format(time.DateOnly), to.Format(time.DateOnly))
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", "text/calendar; charset=utf-8")
	w.Header().Set("Content-Disposition", `inline; filename="shifts.ics"`)
	_, err = io.WriteString(w, ICS(days, ClosingDates(from, to, st.ClosingDays, custom), st.PersonName, now))
	return err
}
