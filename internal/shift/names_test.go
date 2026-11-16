package shift

import "testing"

func TestResolve(t *testing.T) {
	known := []string{"タナカ ユイ", "スズキ ケン", "スズキ ケイ", "オオバヤシ ミドリ"}
	fixes := map[string]string{nameKey("ヤマタ"): "山田 太郎"}
	for _, c := range []struct{ in, want string }{
		{"タナカユイ", "タナカ ユイ"},        // spacing differs
		{"タナカ ユエ", "タナカ ユイ"},       // a character off, one match
		{"スズキ ケソ", "スズキ ケソ"},       // as close to ケン as to ケイ: don't guess
		{"ヤマタ", "山田 太郎"},           // a remembered correction wins
		{"サトウ ミホ", "サトウ ミホ"},       // someone new
		{"オオバヤジ ミドリ", "オオバヤシ ミドリ"}, // two off allowed for a long name
		{"ケン", "ケン"},               // too short to match
	} {
		if got := Resolve(c.in, fixes, known); got != c.want {
			t.Errorf("Resolve(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
