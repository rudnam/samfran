-- Places (internal/restaurant, photo.go): the photo you chose as a place's
-- cover, shown in the list in its category's place. Null shows the newest
-- photo, as before; deleting the chosen one goes back to that.
ALTER TABLE restaurants ADD COLUMN cover_photo_id BIGINT REFERENCES restaurant_photos (id) ON DELETE SET NULL;
