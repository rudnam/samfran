package shift

import "testing"

func TestMealRemindersValidation(t *testing.T) {
	defs := DefaultMealReminders()
	if !mealRemindersEqual(defs, DefaultMealReminders()) {
		t.Fatal("defaults should equal themselves")
	}
	if defs["Early"].Breakfast != (MealRule{Mode: MealFixed, Time: "05:00"}) {
		t.Fatalf("default Early breakfast = %+v, want 05:00 fixed", defs["Early"].Breakfast)
	}

	// "H:MM" normalizes to "HH:MM".
	cleaned, err := cleanMealReminders(map[string]DayMeals{
		"Middle": {
			Breakfast: MealRule{Mode: MealFixed, Time: "8:05"},
			Lunch:     MealRule{Mode: MealFixed, Time: "13:00"},
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

	// Bad times and modes.
	for _, bad := range []string{"", "24:00", "12:60", "ab:cd", "1200"} {
		_, err := cleanMealReminders(map[string]DayMeals{
			"Early": {Breakfast: MealRule{Mode: MealFixed, Time: bad}, Lunch: MealRule{Mode: MealOff}, Dinner: MealRule{Mode: MealOff}},
		})
		if err == nil || err.Error() != "400 invalid meal reminder time" {
			t.Errorf("time %q: err = %v", bad, err)
		}
	}
	_, err = cleanMealReminders(map[string]DayMeals{
		"Early": {Breakfast: MealRule{Mode: MealRuleMode("before_start")}, Lunch: MealRule{Mode: MealOff}, Dinner: MealRule{Mode: MealOff}},
	})
	if err == nil || err.Error() != "400 invalid meal reminder time" {
		t.Errorf("unsupported mode: err = %v", err)
	}
}
