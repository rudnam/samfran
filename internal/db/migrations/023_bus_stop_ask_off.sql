-- The bus-stop alarm (internal/bus): the question at the end of a shift is
-- now switched on stop by stop, instead of once on each phone. Stops are a
-- person's own but the shifts are one person's, so a stop asks only once
-- it's switched on: off for new stops, and off for all until each phone
-- that had the question on switches it on for its person's stops (the
-- Android app does that once, when it updates).
ALTER TABLE bus_saved_stops ALTER COLUMN ask SET DEFAULT false;
UPDATE bus_saved_stops SET ask = false;
