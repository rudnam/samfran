package shift

import (
	"sort"
	"time"
	"unicode/utf8"
)

// Who else is there during a shift: besides those on exactly the same shift
// (With), the people on other shifts whose hours overlap it, including a
// night shift from the day before that runs into the morning, and an early
// shift the next morning that a night shift runs into. Only those who
// overlap are kept, and only on the person's working days.

// Coworker is someone else at work on or around a day, as read off the sheet.
type Coworker struct {
	Name string `json:"name"`
	Code string `json:"code"`
	// DayOffset is which day their cell is on: -1 the day before (a night
	// shift running into this one), 1 the next day (an early shift this one
	// runs into), 0 the same day.
	DayOffset int `json:"day_offset,omitempty"`
}

// There is someone else at work during a shift, with when they're there.
type There struct {
	Coworker
	Start *time.Time `json:"start,omitempty"`
	End   *time.Time `json:"end,omitempty"`
}

// maxOthers is how many others a day can list as read (before keeping only
// those who overlap) and maxThere how many are kept.
const (
	maxOthers = 80
	maxThere  = 40
)

// cleanOthers tidies others as read or sent back: names as cleanNames does,
// codes normalized, and nothing that isn't a working code.
func cleanOthers(in []Coworker) ([]Coworker, error) {
	if len(in) > maxOthers {
		return nil, invalidf("at most %d others a day", maxOthers)
	}
	var out []Coworker
	for _, o := range in {
		names, err := cleanNames([]string{o.Name})
		if err != nil {
			return nil, err
		}
		code := normalize(o.Code)
		if len(names) == 0 || !workCode.MatchString(code) || o.DayOffset < -1 || o.DayOffset > 1 || utf8.RuneCountInString(code) > maxCode {
			continue
		}
		out = append(out, Coworker{Name: names[0], Code: code, DayOffset: o.DayOffset})
	}
	return out, nil
}

// overlapping are the others there during day's shift, by when they start:
// not those already in with, and nobody if the shift has no times.
func overlapping(day Day, with []string, others []Coworker, lengths map[string]float64, zone *time.Location) []There {
	if day.Kind != Work || day.Start == nil || day.End == nil {
		return nil
	}
	date, err := time.Parse(time.DateOnly, day.Date)
	if err != nil {
		return nil
	}
	seen := map[string]bool{}
	for _, n := range with {
		seen[nameKey(n)] = true
	}
	var out []There
	for _, o := range others {
		if seen[nameKey(o.Name)] {
			continue
		}
		e := Explain(date.AddDate(0, 0, o.DayOffset).Format(time.DateOnly), o.Code, lengths, zone)
		if e.Start == nil || e.End == nil || !e.Start.Before(*day.End) || !e.End.After(*day.Start) {
			continue
		}
		seen[nameKey(o.Name)] = true
		out = append(out, There{Coworker: o, Start: e.Start, End: e.End})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Start.Before(*out[j].Start) })
	if len(out) > maxThere {
		out = out[:maxThere]
	}
	return out
}

// othersOf is what's stored for those there: who they are and their code.
func othersOf(there []There) []Coworker {
	out := make([]Coworker, 0, len(there))
	for _, t := range there {
		out = append(out, t.Coworker)
	}
	return out
}
