-- Where each place is (internal/restaurant/locate.go), for sorting by
-- distance and the map. Null until known. A place added from the search
-- comes with its position; others, and those saved before, are looked up in
-- the background from their map link or name. located says that was tried,
-- so a place that can't be found isn't asked about again until its link
-- changes.
ALTER TABLE restaurants ADD COLUMN lat DOUBLE PRECISION;
ALTER TABLE restaurants ADD COLUMN lng DOUBLE PRECISION;
ALTER TABLE restaurants ADD COLUMN located BOOLEAN NOT NULL DEFAULT false;
