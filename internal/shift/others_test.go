package shift

import (
	"strings"
	"testing"
	"time"
)

func TestOverlapping(t *testing.T) {
	zone, _ := time.LoadLocation("Asia/Tokyo")
	lengths := map[string]float64{"F": 9, "D": 6}      // not the defaults: the hours below are worked out from these
	sam := Explain("2026-11-18", "13F", lengths, zone) // 13:00–22:00
	others := []Coworker{
		{Name: "サトウ ミオ", Code: "17D"},                // 17–23: overlaps
		{Name: "ヤマダ ハナ", Code: "9F"},                 // 9–18: overlaps, starts first
		{Name: "コバヤシ リク", Code: "7D"},                // 7–13: ends as Sam starts, not there
		{Name: "タナカ ユイ", Code: "13F"},                // already in with
		{Name: "ヨル タロウ", Code: "22F", DayOffset: -1}, // 22–7 the night before: gone by 13
		{Name: "ナカ ジロウ", Code: "20D", DayOffset: -1}, // 20–2 the night before: gone
		{Name: "ヤマダ ハナ", Code: "9F"},                 // twice: once
		{Name: "ナゾ", Code: "9Z"},                     // unknown length: left out
	}
	got := overlapping(sam, []string{"タナカ ユイ"}, others, lengths, zone)
	var names []string
	for _, g := range got {
		names = append(names, g.Name)
	}
	if strings.Join(names, "|") != "ヤマダ ハナ|サトウ ミオ" {
		t.Errorf("there during 13F: %v", names)
	}

	// An early shift: the night before runs into it.
	early := Explain("2026-11-18", "6D", lengths, zone) // 6–12
	got = overlapping(early, nil, []Coworker{{Name: "ヨル タロウ", Code: "22F", DayOffset: -1}, {Name: "ヒル", Code: "12F"}}, lengths, zone)
	if len(got) != 1 || got[0].Name != "ヨル タロウ" || got[0].Start.Format("01-02 15:04") != "11-17 22:00" || got[0].End.Format("01-02 15:04") != "11-18 07:00" {
		t.Errorf("there during 6D: %+v", got)
	}

	// A night shift: it runs into the next morning's early shift.
	night := Explain("2026-11-18", "22F", lengths, zone) // 22–7
	got = overlapping(night, nil, []Coworker{{Name: "アサ", Code: "6D", DayOffset: 1}, {Name: "オソ", Code: "9F", DayOffset: 1}}, lengths, zone)
	if len(got) != 1 || got[0].Name != "アサ" {
		t.Errorf("there during 22F: %+v", got)
	}

	// No times, nobody.
	if got := overlapping(Explain("2026-11-18", "普", lengths, zone), nil, others, lengths, zone); got != nil {
		t.Errorf("a day off: %+v", got)
	}
}

func TestFileOthers(t *testing.T) {
	s := Sheet{Days: []SheetDay{{Day: 2, Code: "13F",
		Same:         []Coworker{{Name: "タナカ", Code: "13Ｆ"}, {Name: "ヤマダ", Code: "9F"}},
		NightBefore:  []Coworker{{Name: "ヨル", Code: "22F"}},
		MorningAfter: []Coworker{{Name: "アサ", Code: "6D"}},
	}}}
	fileOthers(&s)
	d := s.Days[0]
	if strings.Join(d.With, "|") != "タナカ" || len(d.Others) != 3 || d.Others[0].Name != "ヤマダ" || d.Others[1].DayOffset != -1 || d.Others[2].DayOffset != 1 {
		t.Errorf("filed: %+v", d)
	}
}

func TestProgressOf(t *testing.T) {
	hint := Month{Year: 2026, Month: 12}
	if p := progressOf(`{"found": true, "name_as_r`, hint); p.NameAsRead != "" || p.DaysRead != 0 || p.DaysInMonth != 31 {
		t.Errorf("before the name: %+v", p)
	}
	text := `{"found":true,"name_as_read":"オトル \"サマンタ\"","year":2026,"month":11,"days":[{"day":1,"code":"普"},{"day":2,"code":"13F","others":[{"name":"x","code":"9F"}]},{"day":3,`
	if p := progressOf(text, hint); p.NameAsRead != `オトル "サマンタ"` || p.DaysRead != 2 || p.DaysInMonth != 30 {
		t.Errorf("three days in: %+v", p)
	}
}

func TestRegistered(t *testing.T) {
	zone, _ := time.LoadLocation("Asia/Tokyo")
	lengths := DefaultLengths
	sam := Explain("2026-11-18", "13F", lengths, zone) // 13:00–22:00
	people := []Coworker{
		{Name: "タナカ ユイ", Code: "13F"},                // the same shift: kept
		{Name: "ヤマダ ハナ", Code: "9F"},                 // 9–18: kept
		{Name: "コバヤシ リク", Code: "7B"},                // 7–12: not there, dropped
		{Name: "ヨル タロウ", Code: "22F", DayOffset: -1}, // gone by 13: dropped
		{Name: "ナゾ", Code: "9Z"},                     // unknown length: kept
	}
	var names []string
	for _, p := range registered(sam, people, lengths, zone) {
		names = append(names, p.Name)
	}
	if strings.Join(names, "|") != "タナカ ユイ|ヤマダ ハナ|ナゾ" {
		t.Errorf("kept for 13F: %v", names)
	}
	// A shift with no length yet keeps everyone; a day off, nobody.
	if got := registered(Explain("2026-11-18", "13Z", lengths, zone), people, lengths, zone); len(got) != len(people) {
		t.Errorf("13Z: %+v", got)
	}
	if got := registered(Explain("2026-11-18", "普", lengths, zone), people, lengths, zone); got != nil {
		t.Errorf("a day off: %+v", got)
	}
}
