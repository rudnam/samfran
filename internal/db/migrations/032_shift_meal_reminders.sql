-- Meal reminders (internal/shift): customizable breakfast, lunch and dinner
-- times per shift kind (Early, Middle, Late, Night) and days off (Off).
-- NULL means the defaults (internal/shift.DefaultMealReminders).
ALTER TABLE shift_settings ADD COLUMN meal_reminders JSONB;
