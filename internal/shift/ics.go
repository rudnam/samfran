package shift

import (
	"strings"
	"time"
)

// ICS writes days as an iCalendar feed (RFC 5545). Shifts are timed events
// in UTC; days off, leave and training are all-day events. Titles use the
// workplace's own words (早番, 遅番, 夜勤…) and the code, after who
// ("Sam"), so the feed reads well next to your own events.
func ICS(days []Day, who string, now time.Time) string {
	if who == "" {
		who = "Shift"
	}
	stamp := now.UTC().Format("20060102T150405Z")
	var b strings.Builder
	line := func(s string) { b.WriteString(fold(s)); b.WriteString("\r\n") }
	line("BEGIN:VCALENDAR")
	line("VERSION:2.0")
	line("PRODID:-//samfran//shifts//EN")
	line("CALSCALE:GREGORIAN")
	line("X-WR-CALNAME:" + escape(who+"'s shifts"))
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
			line("SUMMARY:" + escape(who+" "+japanese(d.Label)+" "+d.Code))
		default:
			next, _ := time.Parse("20060102", date)
			line("DTSTART;VALUE=DATE:" + date)
			line("DTEND;VALUE=DATE:" + next.AddDate(0, 0, 1).Format("20060102"))
			title := who + " " + japanese(d.Label)
			if d.Kind == Work { // a shift whose length isn't known
				title += " " + d.Code
			}
			line("SUMMARY:" + escape(title))
			// Days off don't make anyone busy.
			line("TRANSP:TRANSPARENT")
		}
		line("END:VEVENT")
	}
	line("END:VCALENDAR")
	return b.String()
}

// japanese is a day's label as the workplace says it.
func japanese(label string) string {
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
