package shift

import (
	"sort"
	"strings"
	"time"
)

// ICS writes days as an iCalendar feed (RFC 5545). Shifts are timed events
// in UTC; days off, leave and training are all-day events. Each title is
// just the workplace's own word for the day (早番, 遅番, 夜勤, 休み…); who
// ("Sam") names the calendar. Coworkers' names are left out: the calendar
// can be shared with people who shouldn't see them. Closing days (YYYY-MM-DD, from ClosingDates)
// are all-day 締め申請通知 events.
func ICS(days []Day, closing []string, who string, now time.Time) string {
	name := "シフト"
	if who != "" {
		name = who + "'s shifts"
	}
	stamp := now.UTC().Format("20060102T150405Z")
	var b strings.Builder
	line := func(s string) { b.WriteString(fold(s)); b.WriteString("\r\n") }
	line("BEGIN:VCALENDAR")
	line("VERSION:2.0")
	line("PRODID:-//samfran//shifts//EN")
	line("CALSCALE:GREGORIAN")
	line("X-WR-CALNAME:" + escape(name))
	// Ask calendar apps to check for changes every few hours.
	line("REFRESH-INTERVAL;VALUE=DURATION:PT3H")
	line("X-PUBLISHED-TTL:PT3H")
	for _, d := range days {
		date := strings.ReplaceAll(d.Date, "-", "")
		line("BEGIN:VEVENT")
		line("UID:" + date + "-shift@samfran")
		line("DTSTAMP:" + stamp)
		switch {
		case d.Start != nil && d.End != nil:
			line("DTSTART:" + d.Start.UTC().Format("20060102T150405Z"))
			line("DTEND:" + d.End.UTC().Format("20060102T150405Z"))
			line("SUMMARY:" + escape(Japanese(d.Label)))
		default:
			next, _ := time.Parse("20060102", date)
			line("DTSTART;VALUE=DATE:" + date)
			line("DTEND;VALUE=DATE:" + next.AddDate(0, 0, 1).Format("20060102"))
			line("SUMMARY:" + escape(Japanese(d.Label)))
			// Days off don't make anyone busy.
			line("TRANSP:TRANSPARENT")
		}
		line("END:VEVENT")
	}
	for _, c := range closing {
		date := strings.ReplaceAll(c, "-", "")
		next, _ := time.Parse("20060102", date)
		line("BEGIN:VEVENT")
		line("UID:" + date + "-closing@samfran")
		line("DTSTAMP:" + stamp)
		line("DTSTART;VALUE=DATE:" + date)
		line("DTEND;VALUE=DATE:" + next.AddDate(0, 0, 1).Format("20060102"))
		line("SUMMARY:" + ClosingTitle)
		line("TRANSP:TRANSPARENT")
		line("END:VEVENT")
	}
	line("END:VCALENDAR")
	return b.String()
}

// ClosingTitle is a closing day's event title: the hours are sent in.
const ClosingTitle = "締め申請通知"

// ClosingDates lists the closing days (days of the month; past the month's
// end means its last day) from the month of from to the month of to, as
// YYYY-MM-DD, in order. custom has the months ("YYYY-MM") whose days were
// set for that month only.
func ClosingDates(from, to time.Time, usual []int, custom map[string][]int) []string {
	var out []string
	month := time.Date(from.Year(), from.Month(), 1, 0, 0, 0, 0, time.UTC)
	end := time.Date(to.Year(), to.Month(), 1, 0, 0, 0, 0, time.UTC)
	for ; !month.After(end); month = month.AddDate(0, 1, 0) {
		last := month.AddDate(0, 1, -1).Day()
		seen := map[int]bool{}
		days, ok := custom[month.Format("2006-01")]
		if !ok {
			days = usual
		}
		for _, d := range days {
			d = min(d, last)
			if d >= 1 && !seen[d] {
				seen[d] = true
				out = append(out, month.AddDate(0, 0, d-1).Format(time.DateOnly))
			}
		}
	}
	sort.Strings(out)
	return out
}

// Japanese is a day's label as the workplace says it.
func Japanese(label string) string {
	if ja, ok := map[string]string{
		"Early": "早番", "Middle": "中番", "Late": "遅番", "Night": "夜勤",
		"Day off": "休み", "Paid leave": "有休", "Training": "研修",
	}[label]; ok {
		return ja
	}
	return label // a code kept as written, e.g. 出張
}

// escape escapes text for an iCalendar value.
func escape(s string) string {
	return strings.NewReplacer(`\`, `\\`, ";", `\;`, ",", `\,`, "\n", `\n`).Replace(s)
}

// fold breaks lines longer than 75 bytes, as iCalendar requires, without
// splitting a UTF-8 character.
func fold(s string) string {
	if len(s) <= 75 {
		return s
	}
	var b strings.Builder
	n := 0
	for _, r := range s {
		size := len(string(r))
		if n+size > 75 {
			b.WriteString("\r\n ")
			n = 1
		}
		b.WriteRune(r)
		n += size
	}
	return b.String()
}
