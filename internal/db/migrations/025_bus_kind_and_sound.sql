-- The ride alarm (internal/bus), for trains as well as buses: each stop is
-- a bus stop or a train station, which sets how the apps word it and how
-- far before it they offer to buzz. Stops saved before are bus stops.
ALTER TABLE bus_saved_stops ADD COLUMN kind TEXT NOT NULL DEFAULT 'bus'
    CHECK (kind IN ('bus', 'train'));

-- And if the buzz isn't stopped, the Android app adds the phone's alarm
-- sound after this many seconds (0: never). A person's, like buzz.
ALTER TABLE bus_stops ADD COLUMN sound_after INTEGER NOT NULL DEFAULT 60
    CHECK (sound_after IN (0, 30, 60, 120));
