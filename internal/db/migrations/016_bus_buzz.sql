-- The bus-stop alarm (internal/bus): how the phone buzzes at the stop.
-- 'gentle' is how it always buzzed (short buzzes at the phone's usual
-- strength); 'strong' (full strength, longer buzzes) is the new default;
-- 'strongest' is full strength with hardly a pause.
ALTER TABLE bus_stops ADD COLUMN buzz TEXT NOT NULL DEFAULT 'strong'
    CHECK (buzz IN ('gentle', 'strong', 'strongest'));
