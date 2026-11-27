package shift

import (
	"testing"
)

func TestMealRemindersValidation(t *testing.T) {
	defs := DefaultMealReminders()
	if !mealRemindersEqual(defs, DefaultMealReminders()) {
		t.Fatal("defaults should equal themselves")
	}

	// "H:MM" normalizes to "HH:MM".
	cleaned, err := cleanMealReminders(map[string]DayMeals{
		"Middle": {
			Breakfast: MealRule{Mode: MealFixed, Time: "8:05"},
			Lunch:     MealRule{Mode: MealBeforeStart, Minutes: 60},
			Dinner:    MealRule{Mode: MealOff},
		},
	})
	if err != nil {
		t.Fatalf("clean valid: %v", err)
	}
	if cleaned["Middle"].Breakfast.Time != "08:05" {
		t.Errorf("normalized time = %q, want 08:05", cleaned["Middle"].Breakfast.Time)
	}
	if cleaned["Early"] != defs["Early"] {
		t.Errorf("omitted key Early = %+v, want default %+v", cleaned["Early"], defs["Early"])
	}

	// Bad times.
	for _, bad := range []string{"", "24:00", "12:60", "ab:cd", "1200"} {
		_, err := cleanMealReminders(map[string]DayMeals{
			"Early": {Breakfast: MealRule{Mode: MealFixed, Time: bad}, Lunch: MealRule{Mode: MealOff}, Dinner: MealRule{Mode: MealOff}},
		})
		if err == nil || err.Error() != "400 invalid meal reminder time" {
			t.Errorf("time %q: err = %v", bad, err)
		}
	}

	// Bad offsets.
	for _, m := range []int{0, -1, 361} {
		_, err := cleanMealReminders(map[string]DayMeals{
			"Early": {Breakfast: MealRule{Mode: MealBeforeStart, Minutes: m}, Lunch: MealRule{Mode: MealOff}, Dinner: MealRule{Mode: MealOff}},
		})
		if err == nil || err.Error() != "400 meal offset must be from 1 to 360 minutes" {
			t.Errorf("minutes %d: err = %v", m, err)
		}
	}

	// Relative times on Off are rejected.
	for _, mode := range []MealRuleMode{MealBeforeStart, MealAfterEnd} {
		_, err := cleanMealReminders(map[string]DayMeals{
			"Off": {Breakfast: MealRule{Mode: mode, Minutes: 60}, Lunch: MealRule{Mode: MealOff}, Dinner: MealRule{Mode: MealOff}},
		})
		if err == nil || err.Error() != "400 relative meal times are only for working shifts" {
			t.Errorf("Off with %s: err = %v", mode, err)
		}
	}
}
