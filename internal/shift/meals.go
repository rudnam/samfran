package shift

import (
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"github.com/rudnam/samfran/internal/httpx"
)

// MealDayKeys are the shift kinds (and "Off" for non-working days) that can
// have meal reminder rules, in display order.
var MealDayKeys = []string{"Early", "Middle", "Late", "Night", "Off"}

// MealRuleMode is how a meal's reminder time is determined.
type MealRuleMode string

const (
	MealFixed       MealRuleMode = "fixed"
	MealBeforeStart MealRuleMode = "before_start"
	MealAfterEnd    MealRuleMode = "after_end"
	MealOff         MealRuleMode = "off"
)

// MealRule is one meal's reminder rule.
type MealRule struct {
	Mode    MealRuleMode `json:"mode"`
	Time    string       `json:"time,omitempty"`    // "HH:MM" 24h when Mode == MealFixed
	Minutes int          `json:"minutes,omitempty"` // 1..360 when Mode == MealBeforeStart or MealAfterEnd
}

// DayMeals is a day's breakfast, lunch and dinner rules.
type DayMeals struct {
	Breakfast MealRule `json:"breakfast"`
	Lunch     MealRule `json:"lunch"`
	Dinner    MealRule `json:"dinner"`
}

var mealTimePattern = regexp.MustCompile(`^([0-9]{1,2}):([0-9]{2})$`)

// DefaultMealReminders returns a fresh copy of the default meal schedule.
func DefaultMealReminders() map[string]DayMeals {
	return map[string]DayMeals{
		"Early": {
			Breakfast: MealRule{Mode: MealBeforeStart, Minutes: 120},
			Lunch:     MealRule{Mode: MealFixed, Time: "11:00"},
			Dinner:    MealRule{Mode: MealFixed, Time: "18:00"},
		},
		"Middle": {
			Breakfast: MealRule{Mode: MealFixed, Time: "08:00"},
			Lunch:     MealRule{Mode: MealFixed, Time: "13:00"},
			Dinner:    MealRule{Mode: MealFixed, Time: "19:00"},
		},
		"Late": {
			Breakfast: MealRule{Mode: MealFixed, Time: "10:00"},
			Lunch:     MealRule{Mode: MealFixed, Time: "15:00"},
			Dinner:    MealRule{Mode: MealFixed, Time: "00:00"},
		},
		"Night": {
			Breakfast: MealRule{Mode: MealFixed, Time: "20:00"},
			Lunch:     MealRule{Mode: MealFixed, Time: "03:00"},
			Dinner:    MealRule{Mode: MealFixed, Time: "08:00"},
		},
		"Off": {
			Breakfast: MealRule{Mode: MealFixed, Time: "08:00"},
			Lunch:     MealRule{Mode: MealFixed, Time: "13:00"},
			Dinner:    MealRule{Mode: MealFixed, Time: "19:00"},
		},
	}
}

func mergeMealReminders(saved []byte) (map[string]DayMeals, error) {
	out := DefaultMealReminders()
	if len(saved) == 0 {
		return out, nil
	}
	var raw map[string]DayMeals
	if err := json.Unmarshal(saved, &raw); err != nil {
		return nil, err
	}
	for _, k := range MealDayKeys {
		if v, ok := raw[k]; ok {
			out[k] = v
		}
	}
	return out, nil
}

func cleanMealRule(r MealRule, working bool) (MealRule, error) {
	switch r.Mode {
	case MealOff:
		return MealRule{Mode: MealOff}, nil
	case MealFixed:
		m := mealTimePattern.FindStringSubmatch(strings.TrimSpace(r.Time))
		if m == nil {
			return MealRule{}, httpx.Errorf(http.StatusBadRequest, "invalid meal reminder time")
		}
		h, _ := strconv.Atoi(m[1])
		min, _ := strconv.Atoi(m[2])
		if h < 0 || h > 23 || min < 0 || min > 59 {
			return MealRule{}, httpx.Errorf(http.StatusBadRequest, "invalid meal reminder time")
		}
		return MealRule{Mode: MealFixed, Time: fmt.Sprintf("%02d:%02d", h, min)}, nil
	case MealBeforeStart, MealAfterEnd:
		if !working {
			return MealRule{}, httpx.Errorf(http.StatusBadRequest, "relative meal times are only for working shifts")
		}
		if r.Minutes < 1 || r.Minutes > 360 {
			return MealRule{}, httpx.Errorf(http.StatusBadRequest, "meal offset must be from 1 to 360 minutes")
		}
		return MealRule{Mode: r.Mode, Minutes: r.Minutes}, nil
	default:
		return MealRule{}, httpx.Errorf(http.StatusBadRequest, "invalid meal reminder time")
	}
}

func cleanMealReminders(in map[string]DayMeals) (map[string]DayMeals, error) {
	out := DefaultMealReminders()
	for _, k := range MealDayKeys {
		dm, ok := in[k]
		if !ok {
			continue
		}
		working := k != "Off"
		b, err := cleanMealRule(dm.Breakfast, working)
		if err != nil {
			return nil, err
		}
		l, err := cleanMealRule(dm.Lunch, working)
		if err != nil {
			return nil, err
		}
		d, err := cleanMealRule(dm.Dinner, working)
		if err != nil {
			return nil, err
		}
		out[k] = DayMeals{Breakfast: b, Lunch: l, Dinner: d}
	}
	return out, nil
}

func mealRemindersEqual(a, b map[string]DayMeals) bool {
	for _, k := range MealDayKeys {
		if a[k] != b[k] {
			return false
		}
	}
	return true
}
