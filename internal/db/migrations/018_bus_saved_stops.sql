-- The bus-stop alarm (internal/bus): each person can keep several stops,
-- as they get off at different ones after different shifts. A stop can be
-- the one for some kinds of shift ('Early', 'Middle', 'Late', 'Night', as
-- internal/shift names them); each kind is on at most one of a person's
-- stops. used_at says which stop was used last, for when no shift says.
--
-- bus_stops stays: it keeps how the phone buzzes (a person's, not a stop's),
-- and a copy of the stop used last, which older apps read and write through
-- /api/bus/stop.
CREATE TABLE bus_saved_stops (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT NOT NULL DEFAULT '',
    lat          DOUBLE PRECISION NOT NULL,
    lng          DOUBLE PRECISION NOT NULL,
    alert_meters INTEGER NOT NULL DEFAULT 800 CHECK (alert_meters BETWEEN 200 AND 5000),
    shifts       TEXT[] NOT NULL DEFAULT '{}',
    used_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX bus_saved_stops_user ON bus_saved_stops (user_id);

INSERT INTO bus_saved_stops (user_id, name, lat, lng, alert_meters, used_at, created_at)
SELECT user_id, name, lat, lng, alert_meters, updated_at, updated_at FROM bus_stops;
