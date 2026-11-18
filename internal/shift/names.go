package shift

import (
	"context"
	"strings"
	"unicode/utf8"
)

// Coworkers' names come off a photo, so they are often a character off. Two
// things make later scans better: the corrections people make while checking
// a scan (shift_name_fixes), and the names already saved on past days, which
// a close misreading is matched to.

// NameFix is a name as misread and what it should have been.
type NameFix struct {
	From string `json:"from"`
	To   string `json:"to"`
}

// maxKnown is how many known names are kept for matching and sent to the reader.
const maxKnown = 200

// nameKey is a name with spaces and case ignored, to compare names that differ
// only in spacing ("タナカ ユイ" and "タナカユイ").
func nameKey(s string) string {
	return strings.ToLower(strings.Join(strings.Fields(s), ""))
}

// distance is the edit distance between two strings, in characters.
func distance(a, b string) int {
	x, y := []rune(a), []rune(b)
	prev := make([]int, len(y)+1)
	for j := range prev {
		prev[j] = j
	}
	for i := 1; i <= len(x); i++ {
		cur := make([]int, len(y)+1)
		cur[0] = i
		for j := 1; j <= len(y); j++ {
			cost := 1
			if x[i-1] == y[j-1] {
				cost = 0
			}
			cur[j] = min(prev[j]+1, cur[j-1]+1, prev[j-1]+cost)
		}
		prev = cur
	}
	return prev[len(y)]
}

// Resolve is the name to use for one read off a sheet: a remembered
// correction, else the same person as already known (spacing aside), else
// the one known name that's a character or two off, else the name as read.
// Ties between known names are left alone: better as read than a guess.
func Resolve(name string, fixes map[string]string, known []string) string {
	k := nameKey(name)
	if k == "" {
		return name
	}
	if to, ok := fixes[k]; ok {
		return to
	}
	best, bestDist, tied := "", 1<<30, false
	for _, n := range known {
		d := distance(k, nameKey(n))
		switch {
		case d < bestDist:
			best, bestDist, tied = n, d, false
		case d == bestDist && n != best:
			tied = true
		}
	}
	// One character off, or two for a long name; never for a very short one.
	limit := 0
	if n := utf8.RuneCountInString(k); n >= 8 {
		limit = 2
	} else if n >= 3 {
		limit = 1
	}
	if bestDist <= limit && !tied {
		return best
	}
	return name
}

// applyNames replaces the coworkers read off a sheet with the names this
// space already knows (see Resolve) and lists what it changed, as "read → used".
func applyNames(sheet *Sheet, fixes map[string]string, known []string) []string {
	var changes []string
	seen := map[string]bool{}
	resolve := func(n string) string {
		to := Resolve(n, fixes, known)
		if nameKey(to) == nameKey(n) && to == strings.Join(strings.Fields(n), " ") {
			return n
		}
		if c := n + " → " + to; !seen[c] && nameKey(to) != nameKey(n) {
			seen[c] = true
			changes = append(changes, c)
		}
		return to
	}
	for i := range sheet.Days {
		for j, n := range sheet.Days[i].With {
			sheet.Days[i].With[j] = resolve(n)
		}
		for j, o := range sheet.Days[i].Others {
			sheet.Days[i].Others[j].Name = resolve(o.Name)
		}
	}
	return changes
}

// cleanFixes tidies corrections sent with a save: no blanks, no no-ops.
func cleanFixes(in []NameFix) ([]NameFix, error) {
	if len(in) > 200 {
		return nil, invalidf("at most 200 corrections at a time")
	}
	var out []NameFix
	for _, f := range in {
		from, err := cleanNames([]string{f.From})
		if err != nil {
			return nil, err
		}
		to, err := cleanNames([]string{f.To})
		if err != nil {
			return nil, err
		}
		if len(from) == 0 || len(to) == 0 || nameKey(from[0]) == nameKey(to[0]) {
			continue
		}
		out = append(out, NameFix{From: from[0], To: to[0]})
	}
	return out, nil
}

// nameFixes returns the space's remembered corrections, by nameKey of the misreading.
func (s *Store) nameFixes(ctx context.Context, spaceID int64) (map[string]string, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT misread, name FROM shift_name_fixes WHERE space_id = $1`, spaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var from, to string
		if err := rows.Scan(&from, &to); err != nil {
			return nil, err
		}
		out[nameKey(from)] = to
	}
	return out, rows.Err()
}

// knownNames returns the coworkers saved on past days and the names fixed to,
// most recent first.
func (s *Store) knownNames(ctx context.Context, spaceID int64) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT name FROM (
			SELECT unnest(coworkers) AS name, max(day)::timestamptz AS at FROM shifts WHERE space_id = $1 GROUP BY 1
			UNION ALL
			SELECT o->>'name', max(day)::timestamptz FROM shifts, jsonb_array_elements(others) o WHERE space_id = $1 GROUP BY 1
			UNION ALL
			SELECT name, updated_at FROM shift_name_fixes WHERE space_id = $1
		) n GROUP BY name ORDER BY max(at) DESC LIMIT $2`, spaceID, maxKnown)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

// saveNameFixes remembers corrections (a newer one for the same misreading wins).
func (s *Store) saveNameFixes(ctx context.Context, spaceID int64, fixes []NameFix) error {
	for _, f := range fixes {
		if _, err := s.db.ExecContext(ctx, `
			INSERT INTO shift_name_fixes (space_id, misread, name) VALUES ($1, $2, $3)
			ON CONFLICT (space_id, misread) DO UPDATE SET name = $3, updated_at = now()`,
			spaceID, f.From, f.To); err != nil {
			return err
		}
	}
	return nil
}
