package shift

import (
	"strings"
	"testing"
	"time"
)

func TestExplain(t *testing.T) {
	tokyo, _ := time.LoadLocation("Asia/Tokyo")
	for _, c := range []struct {
		code, kind, label, start, end, problem string
	}{
		{"13F", "work", "Late", "2026-11-04T13:00:00+09:00", "2026-11-04T21:00:00+09:00", ""},
		{"７Ｆ", "work", "Early", "2026-11-04T07:00:00+09:00", "2026-11-04T15:00:00+09:00", ""}, // full-width
		{"9d", "work", "Middle", "2026-11-04T09:00:00+09:00", "2026-11-04T15:00:00+09:00", ""},
		{"22F", "work", "Night", "2026-11-04T22:00:00+09:00", "2026-11-05T06:00:00+09:00", ""}, // ends next morning
		{"13Z", "work", "Late", "", "", "unknown length for Z"},
		{"25F", "work", "Night", "", "", "no such start hour"},
		{"普", "off", "Day off", "", "", ""},
		{"法", "off", "Day off", "", "", ""},
		{"有休", "leave", "Paid leave", "", "", ""},
		{"トレ", "training", "Training", "", "", ""},
		{"出張", "other", "出張", "", "", ""},
	} {
		d := Explain("2026-11-04", c.code, DefaultLengths, tokyo)
		start, end := "", ""
		if d.Start != nil {
			start, end = d.Start.Format(time.RFC3339), d.End.Format(time.RFC3339)
		}
		if string(d.Kind) != c.kind || d.Label != c.label || start != c.start || end != c.end || d.Problem != c.problem {
			t.Errorf("%s: %+v (start %s end %s)", c.code, d, start, end)
		}
	}
}

func TestICS(t *testing.T) {
	tokyo, _ := time.LoadLocation("Asia/Tokyo")
	days := []Day{
		Explain("2026-11-06", "22F", DefaultLengths, tokyo),
		Explain("2026-11-08", "普", DefaultLengths, tokyo),
	}
	got := ICS(days, "Sam, the best", time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC))
	for _, want := range []string{
		"BEGIN:VCALENDAR\r\n",
		"DTSTART:20261106T130000Z\r\n", // 22:00 in Tokyo
		"DTEND:20261106T210000Z\r\n",
		`SUMMARY:Sam\, the best: Night (22F)` + "\r\n",
		"DTSTART;VALUE=DATE:20261108\r\nDTEND;VALUE=DATE:20261109\r\n",
		"TRANSP:TRANSPARENT\r\n",
		"END:VCALENDAR\r\n",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("feed lacks %q:\n%s", want, got)
		}
	}
	for _, l := range strings.Split(got, "\r\n") {
		if len(l) > 75 {
			t.Errorf("line longer than 75 bytes: %q", l)
		}
	}
}
