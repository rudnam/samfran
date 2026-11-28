package shift

import (
	"regexp"
	"strconv"
	"strings"
	"time"
)

// A shift sheet's codes. Working days are a start hour and a letter for
// how long the shift is ("13F": from 13:00, F long). Sheets from December
// 2026 write them with up to three letters and may put a few letters before
// the hour ("HPA 13LE"): the letters after the hour are what has a length,
// and those before it are kept as written but don't change the times. The letters' lengths
// come from the sheet's key and are a setting (Lengths), because they can
// change and were read off a photo. Everything else is a day off, leave or
// training, with no times.

// Kind is what a day is.
type Kind string

const (
	Work     Kind = "work"
	Off      Kind = "off"      // 普, 法, 休, 公
	Leave    Kind = "leave"    // 有休: paid leave
	Training Kind = "training" // トレ, 研修
	Other    Kind = "other"    // anything else, kept as written
)

// DefaultLengths are the hours on site (work plus break) for each letter.
// They're the 拘束時間 column of the key at the bottom of the sheet (労働時間,
// the hours worked, is this minus the break); F is confirmed (13F ends at
// 22:00, 7F at 16:00). They're a setting.
var DefaultLengths = map[string]float64{
	"A": 4, "B": 5, "W": 5.5, "D": 8, "E": 8, "F": 9, "G": 10,
	"H": 10, "I": 11, "J": 11, "K": 12, "L": 12,
	// The December 2026 sheet's letters: JE is D's length, LE is F's, OE is G's.
	"JE": 8, "LE": 9, "OE": 10,
}

var workCode = regexp.MustCompile(`^[A-Z]{0,4}?([0-9]{1,2})([A-Z]{1,3})$`)

// Day is one day of the sheet, worked out.
type Day struct {
	Date string `json:"date"` // YYYY-MM-DD
	Code string `json:"code"` // as on the sheet
	Kind Kind   `json:"kind"`
	// Label is a short name: "Early", "Late", "Night", "Day off"…
	Label string `json:"label"`
	// Start and End, for working days whose length is known.
	Start *time.Time `json:"start,omitempty"`
	End   *time.Time `json:"end,omitempty"`
	// Problem says why a working day has no times (an unknown letter).
	Problem string `json:"problem,omitempty"`
	// With are the others on the same shift (the same code that day), by
	// their names as printed on the sheet. Only for working days.
	With []string `json:"with,omitempty"`
	// Others are the others on other shifts whose hours overlap this one,
	// by when they start (others.go). Only for working days with times.
	Others []There `json:"others,omitempty"`
	// People are everyone kept for the day (others.go): those on its shift
	// or there during it, as read or saved. With and Others are worked out
	// from them. Only for working days.
	People []Coworker `json:"people,omitempty"`
}

// normalize tidies a code as typed or read: full-width letters and digits
// become ASCII, spaces go.
func normalize(code string) string {
	var b strings.Builder
	for _, r := range strings.TrimSpace(code) {
		switch {
		case r >= '０' && r <= '９':
			r = '0' + (r - '０')
		case r >= 'Ａ' && r <= 'Ｚ':
			r = 'A' + (r - 'Ａ')
		case r >= 'ａ' && r <= 'ｚ':
			r = 'A' + (r - 'ａ')
		case r >= 'a' && r <= 'z':
			r = 'A' + (r - 'a')
		case r == ' ' || r == '　':
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

// Explain works out one day: what kind it is and, for a shift, when it
// starts and ends in zone.
func Explain(date, code string, lengths map[string]float64, zone *time.Location) Day {
	code = normalize(code)
	d := Day{Date: date, Code: code}
	switch {
	case workCode.MatchString(code):
		m := workCode.FindStringSubmatch(code)
		hour, _ := strconv.Atoi(m[1])
		d.Kind, d.Label = Work, shiftLabel(hour)
		hours, ok := lengths[m[2]]
		if !ok || hours <= 0 || hour > 23 {
			d.Problem = "unknown length for " + m[2]
			if hour > 23 {
				d.Problem = "no such start hour"
			}
			return d
		}
		day, err := time.ParseInLocation(time.DateOnly, date, zone)
		if err != nil {
			d.Problem = "bad date"
			return d
		}
		start := day.Add(time.Duration(hour) * time.Hour)
		end := start.Add(time.Duration(hours * float64(time.Hour)))
		d.Start, d.End = &start, &end
	case code == "普" || code == "法" || code == "休" || code == "公" || code == "公休":
		d.Kind, d.Label = Off, "Day off"
	case code == "有休" || code == "有" || code == "有給":
		d.Kind, d.Label = Leave, "Paid leave"
	case code == "トレ" || code == "研修":
		d.Kind, d.Label = Training, "Training"
	default:
		d.Kind, d.Label = Other, code
	}
	return d
}

// shiftLabel names a shift by when it starts, as the sheet's totals do
// (早番, 中番, 遅番, ナイト).
func shiftLabel(hour int) string {
	switch {
	case hour < 9:
		return "Early"
	case hour < 12:
		return "Middle"
	case hour < 18:
		return "Late"
	default:
		return "Night"
	}
}
