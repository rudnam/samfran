package mcp

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/rudnam/samfran/internal/db"
	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/restaurant"
	"github.com/rudnam/samfran/internal/shift"
)

// call is one tool call by a signed-in user, in their space.
type call struct {
	r       *http.Request
	h       *Handler
	userID  int64
	spaceID int64
}

// space finds the user's space the first time a tool needs it.
func (c *call) space() (int64, error) {
	if c.spaceID == 0 {
		id, err := c.h.spaces.Of(c.r.Context(), c.userID)
		if err != nil {
			return 0, err
		}
		c.spaceID = id
	}
	return c.spaceID, nil
}

// toolError is a problem the model should see and can fix, e.g. a bad
// argument; other errors are logged and reported as internal.
type toolError struct{ msg string }

func (e *toolError) Error() string { return e.msg }

func toolErrorf(format string, args ...any) error { return &toolError{fmt.Sprintf(format, args...)} }

// asToolError turns the features' expected errors (bad input, not found)
// into tool errors the model can act on.
func asToolError(err error, what string) error {
	var he *httpx.Error
	var inv *shift.InvalidError
	switch {
	case errors.Is(err, db.ErrNotFound):
		return toolErrorf("no such %s", what)
	case errors.As(err, &he):
		return toolErrorf("%s", he.Message)
	case errors.As(err, &inv):
		return toolErrorf("%s", inv.Msg)
	}
	return err
}

var errBadID = errors.New("bad id")

func parseID(s string) (int64, error) {
	id, err := strconv.ParseInt(s, 10, 64)
	if err != nil || id <= 0 {
		return 0, errBadID
	}
	return id, nil
}

type tool struct {
	Name        string         `json:"name"`
	Title       string         `json:"title"`
	Description string         `json:"description"`
	InputSchema map[string]any `json:"inputSchema"`
	Annotations map[string]any `json:"annotations"`
	run         func(c *call, args json.RawMessage) (any, error)
}

// decode reads a tool's arguments into dst.
func decode(args json.RawMessage, dst any) error {
	if err := json.Unmarshal(args, dst); err != nil {
		return toolErrorf("bad arguments: %v", err)
	}
	return nil
}

func object(props map[string]any, required ...string) map[string]any {
	s := map[string]any{"type": "object", "properties": props, "additionalProperties": false}
	if len(required) > 0 {
		s["required"] = required
	}
	return s
}

var (
	readOnly = map[string]any{"readOnlyHint": true, "openWorldHint": false}
	adds     = map[string]any{"readOnlyHint": false, "destructiveHint": false, "idempotentHint": false, "openWorldHint": false}
	replaces = map[string]any{"readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": false}
	str      = func(desc string) map[string]any { return map[string]any{"type": "string", "description": desc} }
	monthArg = str("A month, YYYY-MM (e.g. 2026-11).")
	dateArg  = str("A date, YYYY-MM-DD.")
	starsArg = map[string]any{"type": "integer", "minimum": 0, "maximum": 5, "description": "1 to 5 stars; 0 clears the rating."}
	idArg    = map[string]any{"type": "integer", "description": "The restaurant's id, from list_restaurants."}
)

var toolList = []*tool{
	{
		Name:  "get_shift_settings",
		Title: "Shift settings",
		Description: "Whose shifts samfran keeps (person_name), their name as printed on the shift sheet (name_on_sheet: read only that row), " +
			"the hours on site for each letter of a shift code (lengths: 13F starts at 13:00 and lasts lengths.F hours), and the time zone. " +
			"Codes: a start hour and a letter for a working day (7F, 13F, 22F; a night shift ends the next morning); 普 and 法 are days off; 有休 is paid leave; トレ and 研修 are training.",
		InputSchema: object(map[string]any{}),
		Annotations: readOnly,
		run: func(c *call, _ json.RawMessage) (any, error) {
			sp, err := c.space()
			if err != nil {
				return nil, err
			}
			st, _, err := c.h.shifts.Settings(c.r.Context(), sp)
			if err != nil {
				return nil, err
			}
			st.FeedPath = "" // the calendar's secret stays in the app
			return st, nil
		},
	},
	{
		Name:        "get_shifts",
		Title:       "Shifts for a month",
		Description: "The saved shifts for a month: each day's code as on the sheet, its kind and label, and start and end times (RFC 3339) for working days.",
		InputSchema: object(map[string]any{"month": monthArg}),
		Annotations: readOnly,
		run: func(c *call, args json.RawMessage) (any, error) {
			var in struct{ Month string }
			if err := decode(args, &in); err != nil {
				return nil, err
			}
			sp, err := c.space()
			if err != nil {
				return nil, err
			}
			view, err := c.h.shifts.Month(c.r.Context(), sp, in.Month, time.Now())
			if err != nil {
				return nil, asToolError(err, "month")
			}
			view.Settings.FeedPath = ""
			return view, nil
		},
	},
	{
		Name:  "save_month_shifts",
		Title: "Save a month of shifts",
		Description: "Replaces a whole month of shifts with the days given: days left out, or with an empty code, are cleared. " +
			"Only call this after showing the user the full month you read (day, weekday, code, times) and getting their OK. " +
			"Give every day that has a code, copied exactly as printed (e.g. 13F, 22F, 普, 法, 有休). Returns the month as saved, with times worked out.",
		InputSchema: object(map[string]any{
			"month": monthArg,
			"days": map[string]any{
				"type": "array",
				"items": object(map[string]any{
					"date": dateArg,
					"code": str("The code as on the sheet."),
				}, "date", "code"),
			},
		}, "month", "days"),
		Annotations: replaces,
		run: func(c *call, args json.RawMessage) (any, error) {
			var in struct {
				Month string
				Days  []shift.DayCode
			}
			if err := decode(args, &in); err != nil {
				return nil, err
			}
			sp, err := c.space()
			if err != nil {
				return nil, err
			}
			view, err := c.h.shifts.SaveMonth(c.r.Context(), sp, in.Month, in.Days)
			if err != nil {
				return nil, asToolError(err, "month")
			}
			view.Settings.FeedPath = ""
			return view, nil
		},
	},
	{
		Name:        "list_restaurants",
		Title:       "Restaurants",
		Description: "The restaurants in samfran: ones you've been to (visit_count > 0, with the latest visit) and ones you want to go to (no visits), with each person's rating.",
		InputSchema: object(map[string]any{
			"list": map[string]any{"type": "string", "enum": []string{"all", "been", "want"}, "description": "Which list; all by default."},
		}),
		Annotations: readOnly,
		run: func(c *call, args json.RawMessage) (any, error) {
			var in struct{ List string }
			if err := decode(args, &in); err != nil {
				return nil, err
			}
			sp, err := c.space()
			if err != nil {
				return nil, err
			}
			list, err := c.h.restaurants.List(c.r.Context(), sp, c.userID)
			if err != nil {
				return nil, err
			}
			out := []restaurant.Restaurant{}
			for _, r := range list {
				if in.List == "been" && r.VisitCount == 0 || in.List == "want" && r.VisitCount > 0 {
					continue
				}
				out = append(out, r)
			}
			return map[string]any{"restaurants": out}, nil
		},
	},
	{
		Name:  "add_restaurant",
		Title: "Add a restaurant",
		Description: "Adds a restaurant. With visited_on it's a place you've been (that visit is logged); without, it goes on the want-to-go list. " +
			"stars is the signed-in user's own rating. Check list_restaurants first so a place isn't added twice.",
		InputSchema: object(map[string]any{
			"name":       str("The restaurant's name."),
			"area":       str("Where it is, e.g. a neighbourhood or station."),
			"cuisine":    str("The kind of food, e.g. Ramen."),
			"map_url":    str("A Google Maps link (https://…), if there is one."),
			"notes":      str("Anything worth remembering: what to order, when to book."),
			"visited_on": dateArg,
			"stars":      starsArg,
		}, "name"),
		Annotations: adds,
		run: func(c *call, args json.RawMessage) (any, error) {
			var in struct {
				restaurant.Fields
				VisitedOn string `json:"visited_on"`
				Stars     int    `json:"stars"`
			}
			if err := decode(args, &in); err != nil {
				return nil, err
			}
			f, err := restaurant.Clean(in.Fields)
			if err != nil {
				return nil, asToolError(err, "restaurant")
			}
			if in.Stars < 0 || in.Stars > 5 {
				return nil, toolErrorf("stars must be 0 to 5")
			}
			on := ""
			if in.VisitedOn != "" {
				if on, _, err = restaurant.CheckVisit(in.VisitedOn, "", time.Now()); err != nil {
					return nil, asToolError(err, "visit")
				}
			}
			sp, err := c.space()
			if err != nil {
				return nil, err
			}
			ctx := c.r.Context()
			id, err := c.h.restaurants.Create(ctx, sp, c.userID, f)
			if err != nil {
				return nil, err
			}
			if on != "" {
				if err := c.h.restaurants.AddVisit(ctx, sp, c.userID, id, on, ""); err != nil {
					return nil, err
				}
			}
			if in.Stars > 0 {
				if err := c.h.restaurants.Rate(ctx, sp, c.userID, id, in.Stars); err != nil {
					return nil, err
				}
			}
			return c.h.restaurants.Get(ctx, sp, c.userID, id)
		},
	},
	{
		Name:        "log_visit",
		Title:       "Log a visit",
		Description: "Records that you went to a restaurant on a date, with an optional note (what you had). A want-to-go place moves to the been-there list.",
		InputSchema: object(map[string]any{"restaurant_id": idArg, "visited_on": dateArg, "note": str("Optional: what you had, how it was.")}, "restaurant_id", "visited_on"),
		Annotations: adds,
		run: func(c *call, args json.RawMessage) (any, error) {
			var in struct {
				RestaurantID int64  `json:"restaurant_id"`
				VisitedOn    string `json:"visited_on"`
				Note         string
			}
			if err := decode(args, &in); err != nil {
				return nil, err
			}
			on, note, err := restaurant.CheckVisit(in.VisitedOn, in.Note, time.Now())
			if err != nil {
				return nil, asToolError(err, "visit")
			}
			sp, err := c.space()
			if err != nil {
				return nil, err
			}
			if err := c.h.restaurants.AddVisit(c.r.Context(), sp, c.userID, in.RestaurantID, on, note); err != nil {
				return nil, asToolError(err, "restaurant")
			}
			return c.h.restaurants.Get(c.r.Context(), sp, c.userID, in.RestaurantID)
		},
	},
	{
		Name:        "rate_restaurant",
		Title:       "Rate a restaurant",
		Description: "Sets the signed-in user's own rating of a restaurant (the other person's rating is theirs to set).",
		InputSchema: object(map[string]any{"restaurant_id": idArg, "stars": starsArg}, "restaurant_id", "stars"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false},
		run: func(c *call, args json.RawMessage) (any, error) {
			var in struct {
				RestaurantID int64 `json:"restaurant_id"`
				Stars        int
			}
			if err := decode(args, &in); err != nil {
				return nil, err
			}
			if in.Stars < 0 || in.Stars > 5 {
				return nil, toolErrorf("stars must be 0 to 5")
			}
			sp, err := c.space()
			if err != nil {
				return nil, err
			}
			if err := c.h.restaurants.Rate(c.r.Context(), sp, c.userID, in.RestaurantID, in.Stars); err != nil {
				return nil, asToolError(err, "restaurant")
			}
			return c.h.restaurants.Get(c.r.Context(), sp, c.userID, in.RestaurantID)
		},
	},
}

var tools = func() map[string]*tool {
	m := make(map[string]*tool, len(toolList))
	for _, t := range toolList {
		if !strings.Contains(t.Name, " ") {
			m[t.Name] = t
		}
	}
	return m
}()
