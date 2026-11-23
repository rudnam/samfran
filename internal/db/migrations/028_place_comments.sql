-- Places (internal/restaurant): what each of you says about a place you've
-- been to, in a thread under it. A comment is its writer's: only they can
-- take it back; it goes when they or the place do.
CREATE TABLE restaurant_comments (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    restaurant_id BIGINT NOT NULL REFERENCES restaurants (id) ON DELETE CASCADE,
    user_id       BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body          TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX restaurant_comments_restaurant_id_idx ON restaurant_comments (restaurant_id, created_at);
CREATE INDEX restaurant_comments_user_id_idx ON restaurant_comments (user_id);
