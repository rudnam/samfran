-- Places (internal/restaurant): the apps no longer list visits, only when you
-- went, so what was written about each visit is copied to the end of its
-- place's notes, where they show it: "2026-11-14 · Harami and the cold
-- noodles", newest first, cut to fit the notes' limit (5000). The visits and
-- their notes stay as they were, for older apps.
UPDATE restaurants r
SET notes = left(CASE WHEN btrim(r.notes) = '' THEN v.block ELSE r.notes || E'\n\n' || v.block END, 5000)
FROM (
	SELECT restaurant_id,
		string_agg(to_char(visited_on, 'YYYY-MM-DD') || ' · ' || btrim(note), E'\n' ORDER BY visited_on DESC, id DESC) AS block
	FROM restaurant_visits
	WHERE btrim(note) <> ''
	GROUP BY restaurant_id
) v
WHERE v.restaurant_id = r.id;
