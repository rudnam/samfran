-- The food list becomes a list of places to go (internal/restaurant): each
-- place has a category, by why you'd go there. 'eat' is what every place was
-- until now, and what an older app's new place gets. The values are checked
-- in Go (restaurant.Categories), so a new one needs no migration.
ALTER TABLE restaurants ADD COLUMN category TEXT NOT NULL DEFAULT 'eat';

-- Places already saved that clearly aren't for eating, from what their Food
-- field or name says. Everything else stays 'eat', and can be changed by hand.
UPDATE restaurants SET category = 'relax'
    WHERE cuisine ~* '(温泉|銭湯|サウナ|スパ|onsen|hot spring|sento|sauna|spa\M)' OR name ~ '(温泉|銭湯|の湯)$';
UPDATE restaurants SET category = 'nature'
    WHERE category = 'eat' AND (cuisine ~* '(公園|庭園|植物園|海岸|ビーチ|砂丘|山\M|park|garden|beach|hiking)' OR name ~ '(公園|庭園|植物園|海岸|砂丘)$');
UPDATE restaurants SET category = 'culture'
    WHERE category = 'eat' AND (cuisine ~* '(神社|寺|城|美術館|博物館|shrine|temple|castle|museum|gallery)' OR name ~ '(神社|大社|美術館|博物館)$');
UPDATE restaurants SET category = 'fun'
    WHERE category = 'eat' AND (cuisine ~* '(水族館|動物園|遊園地|テーマパーク|aquarium|zoo|theme park|amusement)' OR name ~ '(水族館|動物園|遊園地)$');
UPDATE restaurants SET category = 'shopping'
    WHERE category = 'eat' AND (cuisine ~* '(ショッピング|モール|アウトレット|shopping|mall|outlet)');
