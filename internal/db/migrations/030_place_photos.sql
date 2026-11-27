-- Places (internal/restaurant, photo.go): photos of a visit, kept exactly as
-- they were taken (never shrunk or re-encoded), with a small thumbnail the
-- apps make for the photo strips. A photo's bytes are split into parts of
-- 1 MiB so the server reads and writes them a part at a time, whatever their
-- size. A photo goes with its visit, and so with its place.
CREATE TABLE restaurant_photos (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    restaurant_id BIGINT NOT NULL REFERENCES restaurants (id) ON DELETE CASCADE,
    visit_id      BIGINT NOT NULL REFERENCES restaurant_visits (id) ON DELETE CASCADE,
    content_type  TEXT NOT NULL,
    size          BIGINT NOT NULL DEFAULT 0,
    -- The original's pixel size, as the app that sent it measured it.
    width         INT NOT NULL DEFAULT 0,
    height        INT NOT NULL DEFAULT 0,
    thumb         BYTEA NOT NULL DEFAULT '',
    thumb_type    TEXT NOT NULL DEFAULT 'image/jpeg',
    added_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX restaurant_photos_visit_id_idx ON restaurant_photos (visit_id, id);
CREATE INDEX restaurant_photos_restaurant_id_idx ON restaurant_photos (restaurant_id, id);

CREATE TABLE restaurant_photo_parts (
    photo_id BIGINT NOT NULL REFERENCES restaurant_photos (id) ON DELETE CASCADE,
    seq      INT NOT NULL,
    data     BYTEA NOT NULL,
    PRIMARY KEY (photo_id, seq)
);
-- Photos are already compressed: don't spend time trying again.
ALTER TABLE restaurant_photo_parts ALTER COLUMN data SET STORAGE EXTERNAL;
