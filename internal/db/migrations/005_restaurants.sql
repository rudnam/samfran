-- Restaurants you've been to, or want to go to (internal/restaurant). One
-- with no visits is on the want-to-go list.
CREATE TABLE restaurants (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    space_id   BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    area       TEXT NOT NULL DEFAULT '',
    cuisine    TEXT NOT NULL DEFAULT '',
    map_url    TEXT NOT NULL DEFAULT '',
    notes      TEXT NOT NULL DEFAULT '',
    added_by   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX restaurants_space_id_idx ON restaurants (space_id);

CREATE TABLE restaurant_visits (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    restaurant_id BIGINT NOT NULL REFERENCES restaurants (id) ON DELETE CASCADE,
    visited_on    DATE NOT NULL,
    note          TEXT NOT NULL DEFAULT '',
    added_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX restaurant_visits_restaurant_id_idx ON restaurant_visits (restaurant_id, visited_on DESC);

-- Each person's own rating of a restaurant, 1 to 5 stars.
CREATE TABLE restaurant_ratings (
    restaurant_id BIGINT NOT NULL REFERENCES restaurants (id) ON DELETE CASCADE,
    user_id       BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    stars         SMALLINT NOT NULL CHECK (stars BETWEEN 1 AND 5),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (restaurant_id, user_id)
);
CREATE INDEX restaurant_ratings_user_id_idx ON restaurant_ratings (user_id);
