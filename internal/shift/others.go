package shift

import (
	"sort"
	"time"
	"unicode/utf8"
)

// Who else is there during a shift. A read lists everyone around each
// working day (People): those working that day, on a night shift from the
// day before, and on an early shift the next morning, each with their code.
// What the apps show is worked out from them whenever the day is: those on
// exactly the same shift (With), and those on other shifts whose hours
// overlap it (Others). While a read is checked, a corrected code changes
// who's shown; once saved, a day keeps only those there during its shift
// (registered).

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

// maxOthers is how many people a day can keep, and maxThere how many are
// shown as there during it.
const (
	maxOthers = 80
	maxThere  = 40
)

// cleanOthers tidies others as read or sent back: names as cleanNames does,
// codes normalized, and nothing that isn't a working code.
func cleanOthers(in []Coworker) ([]Coworker, error) {
	if len(in) > maxOthers {
		return nil, invalidf("at most %d people a day", maxOthers)
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

// cleanPeople tidies a day's people as read or sent: as cleanOthers, and
// each person once (the first time they're listed).
func cleanPeople(in []Coworker) ([]Coworker, error) {
	people, err := cleanOthers(in)
	if err != nil {
		return nil, err
	}
	seen := map[string]bool{}
	out := []Coworker{}
	for _, p := range people {
		if k := nameKey(p.Name); !seen[k] {
			seen[k] = true
			out = append(out, p)
		}
	}
	return out, nil
}

// legacyPeople are a day's people from what older app versions send and
// earlier releases stored: names on the same shift (with), on the day's
// code, and others with their own.
func legacyPeople(code string, with []string, others []Coworker) []Coworker {
	out := make([]Coworker, 0, len(with)+len(others))
	for _, n := range with {
		out = append(out, Coworker{Name: n, Code: code})
	}
	return append(out, others...)
}

// sameShift are the names of those on exactly day's shift: the same code
// that day.
func sameShift(day Day, people []Coworker) []string {
	var out []string
	for _, p := range people {
		if p.DayOffset == 0 && normalize(p.Code) == day.Code {
			out = append(out, p.Name)
		}
	}
	return out
}

// registered are the people a saved day keeps: those on its shift or there
// during it. When that can't be told (the day's or their letter has no
// length yet), they're kept, so setting the length later shows them.
func registered(day Day, people []Coworker, lengths map[string]float64, zone *time.Location) []Coworker {
	if day.Kind != Work {
		return nil
	}
	if day.Start == nil || day.End == nil {
		return people
	}
	date, err := time.Parse(time.DateOnly, day.Date)
	if err != nil {
		return people
	}
	out := []Coworker{}
	for _, p := range people {
		if p.DayOffset == 0 && normalize(p.Code) == day.Code {
			out = append(out, p)
			continue
		}
		e := Explain(date.AddDate(0, 0, p.DayOffset).Format(time.DateOnly), p.Code, lengths, zone)
		if e.Start == nil || e.End == nil || (e.Start.Before(*day.End) && e.End.After(*day.Start)) {
			out = append(out, p)
		}
	}
	return out
}

// withPeople fills in who's around a worked-out day: its people, those on
// the same shift, and the others there during it. Days off keep nobody.
func withPeople(day Day, people []Coworker, lengths map[string]float64, zone *time.Location) Day {
	if day.Kind != Work || len(people) == 0 {
		return day
	}
	day.People = people
	day.With = sameShift(day, people)
	day.Others = overlapping(day, day.With, people, lengths, zone)
	return day
}
