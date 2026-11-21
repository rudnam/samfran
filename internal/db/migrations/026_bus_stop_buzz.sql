-- The ride alarm (internal/bus): how the phone buzzes is now each stop's,
-- not each person's, so a stop you might sleep through can buzz harder.
-- Every stop starts with its person's buzz. bus_stops.buzz stays, for older
-- apps, which buzz one way at every stop.
ALTER TABLE bus_saved_stops ADD COLUMN buzz TEXT NOT NULL DEFAULT 'strong'
    CHECK (buzz IN ('gentle', 'strong', 'strongest'));
UPDATE bus_saved_stops s SET buzz = b.buzz FROM bus_stops b WHERE b.user_id = s.user_id;
