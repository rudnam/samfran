-- The bus-stop alarm (internal/bus): where each person gets off, and how
-- far before it their phone should wake them. Per person, not per space:
-- it's the phone of whoever rides the bus. The phone tracks the trip itself;
-- the server never stores where anyone is.
CREATE TABLE bus_stops (
    user_id      BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT NOT NULL DEFAULT '',
    lat          DOUBLE PRECISION NOT NULL,
    lng          DOUBLE PRECISION NOT NULL,
    -- Ring when this close to the stop, in metres.
    alert_meters INTEGER NOT NULL DEFAULT 800 CHECK (alert_meters BETWEEN 200 AND 5000),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
