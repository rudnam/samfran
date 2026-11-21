-- The ride alarm (internal/bus): whether the alarm sound joins the buzz
-- when it isn't stopped is now each stop's, like how it buzzes. Every stop
-- starts with its person's (from 025); bus_stops.sound_after goes, as no
-- released app used it.
ALTER TABLE bus_saved_stops ADD COLUMN sound_after INTEGER NOT NULL DEFAULT 60
    CHECK (sound_after IN (0, 30, 60, 120));
UPDATE bus_saved_stops s SET sound_after = b.sound_after FROM bus_stops b WHERE b.user_id = s.user_id;
ALTER TABLE bus_stops DROP COLUMN sound_after;
