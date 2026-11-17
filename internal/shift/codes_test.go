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
		{"13F", "work", "Late", "2026-11-04T13:00:00+09:00", "2026-11-04T22:00:00+09:00", ""},
		{"７Ｆ", "work", "Early", "2026-11-04T07:00:00+09:00", "2026-11-04T16:00:00+09:00", ""}, // full-width
		{"9d", "work", "Middle", "2026-11-04T09:00:00+09:00", "2026-11-04T15:00:00+09:00", ""},
		{"22F", "work", "Night", "2026-11-04T22:00:00+09:00", "2026-11-05T07:00:00+09:00", ""}, // ends next morning
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

func TestCellCode(t *testing.T) {
	for _, c := range []struct{ code, note, want string }{
		{"13F", "", "13F"},
		{"13F", "有休", "有休"}, // a small 有休 under the code: paid leave
		{"7F", " 有給 ", "有休"},
		{"13F", "研修", "13F"}, // other notes don't change the day
	} {
		if got := CellCode(c.code, c.note); got != c.want {
			t.Errorf("CellCode(%q, %q) = %q, want %q", c.code, c.note, got, c.want)
		}
	}
}

func TestICS(t *testing.T) {
	tokyo, _ := time.LoadLocation("Asia/Tokyo")
	days := []Day{
		Explain("2026-11-06", "22F", DefaultLengths, tokyo),
		Explain("2026-11-08", "普", DefaultLengths, tokyo),
	}
	got := ICS(days, []string{"2026-11-15"}, "Sam, the best", time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC))
	for _, want := range []string{
		"BEGIN:VCALENDAR\r\n",
		"DTSTART:20261106T130000Z\r\n", // 22:00 in Tokyo
		"DTEND:20261106T220000Z\r\n",
		"SUMMARY:夜勤\r\n",
		"SUMMARY:休み\r\n",
		`X-WR-CALNAME:Sam\, the best's shifts` + "\r\n",
		"DTSTART;VALUE=DATE:20261108\r\nDTEND;VALUE=DATE:20261109\r\n",
		"TRANSP:TRANSPARENT\r\n",
		"UID:20261115-closing@samfran\r\nDTSTAMP:20261101T000000Z\r\nDTSTART;VALUE=DATE:20261115\r\nDTEND;VALUE=DATE:20261116\r\nSUMMARY:締め申請通知\r\n",
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

func TestClosingDates(t *testing.T) {
	from := time.Date(2026, 1, 20, 0, 0, 0, 0, time.UTC)
	to := time.Date(2026, 3, 2, 0, 0, 0, 0, time.UTC)
	got := strings.Join(ClosingDates(from, to, []int{31, 15, 30}, nil), " ")
	// February has no 30th or 31st: both mean the 28th, once.
	want := "2026-01-15 2026-01-30 2026-01-31 2026-02-15 2026-02-28 2026-03-15 2026-03-30 2026-03-31"
	if got != want {
		t.Errorf("ClosingDates = %s, want %s", got, want)
	}
	// February set by hand: the 20th only, and none in March.
	custom := map[string][]int{"2026-02": {20}, "2026-03": {}}
	got = strings.Join(ClosingDates(from, to, []int{15, 31}, custom), " ")
	if want := "2026-01-15 2026-01-31 2026-02-20"; got != want {
		t.Errorf("ClosingDates with February's own = %s, want %s", got, want)
	}
	if got := ClosingDates(from, to, nil, nil); len(got) != 0 {
		t.Errorf("no closing days: %v", got)
	}
}
