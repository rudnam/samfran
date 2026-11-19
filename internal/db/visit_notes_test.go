package db_test

import (
	"os"
	"strings"
	"testing"

	"github.com/rudnam/samfran/internal/testdb"
)

// Migration 024 copies what was written about each visit into its place's
// notes, since the apps no longer list visits; the visits stay as they were.
func TestVisitNotesMoveToPlaceNotes(t *testing.T) {
	conn := testdb.Open(t)
	body, err := os.ReadFile("migrations/024_visit_notes_to_place_notes.sql")
	if err != nil {
		t.Fatal(err)
	}
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := conn.Exec(q, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec(`INSERT INTO spaces DEFAULT VALUES`)
	exec(`INSERT INTO restaurants (space_id, name, notes) VALUES
		(1, 'Hanabi Grill', 'Book the window seats.'),
		(1, 'Menya Kaze', ''),
		(1, 'Cafe Lune', 'Cash only'),
		(1, 'Long notes', $1)`, strings.Repeat("x", 4990))
	exec(`INSERT INTO restaurant_visits (restaurant_id, visited_on, note) VALUES
		(1, '2026-08-21', 'Birthday dinner'),
		(1, '2026-11-14', ' Harami and the cold noodles '),
		(1, '2026-10-02', ''),
		(2, '2026-11-03', 'Spicy miso, extra egg'),
		(3, '2026-09-18', '  '),
		(4, '2026-01-01', 'cut off here')`)

	exec(string(body))

	want := map[string]string{
		"Hanabi Grill": "Book the window seats.\n\n2026-11-14 · Harami and the cold noodles\n2026-08-21 · Birthday dinner",
		"Menya Kaze":   "2026-11-03 · Spicy miso, extra egg",
		"Cafe Lune":    "Cash only",                                // no notes on its visits: untouched
		"Long notes":   strings.Repeat("x", 4990) + "\n\n2026-01-", // cut to the notes' limit
	}
	rows, err := conn.Query(`SELECT name, notes FROM restaurants`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var name, notes string
		if err := rows.Scan(&name, &notes); err != nil {
			t.Fatal(err)
		}
		if notes != want[name] {
			t.Errorf("%s: notes %q, want %q", name, notes, want[name])
		}
	}
	var visits, withNotes int
	conn.QueryRow(`SELECT count(*), count(*) FILTER (WHERE btrim(note) <> '') FROM restaurant_visits`).Scan(&visits, &withNotes)
	if visits != 6 || withNotes != 4 {
		t.Errorf("visits after: %d, %d with notes; want 6 and 4 (left as they were)", visits, withNotes)
	}
}
