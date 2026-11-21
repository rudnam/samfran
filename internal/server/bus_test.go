package server_test

import (
	"fmt"
	"testing"
)

func TestBusStop(t *testing.T) {
	ts := newTestServer(t)
	sam, fran := newClient(t, ts), newClient(t, ts)
	sam.signup("sam@example.com")
	fran.signup("fran@example.com")
	var st struct {
		Set         bool
		Name        string
		Lat, Lng    float64
		AlertMeters int `json:"alert_meters"`
		Buzz        string
	}
	if sam.do("GET", "/api/bus/stop", nil, &st); st.Set || st.AlertMeters != 800 || st.Buzz != "strong" {
		t.Fatalf("before: %+v", st)
	}
	for _, bad := range []map[string]any{
		{"name": "x"},
		{"name": "x", "lat": 91.0, "lng": 0.0},
		{"name": "x", "lat": 35.0, "lng": 137.0, "alert_meters": 50},
		{"name": "x", "lat": 35.0, "lng": 137.0, "buzz": "loud"},
	} {
		if code := sam.do("PUT", "/api/bus/stop", bad, nil); code != 400 {
			t.Errorf("%v: %d", bad, code)
		}
	}
	if code := sam.do("PUT", "/api/bus/stop", map[string]any{"name": " Home stop ", "lat": 34.7111, "lng": 137.7265}, &st); code != 200 ||
		!st.Set || st.Name != "Home stop" || st.Lat != 34.7111 || st.AlertMeters != 800 || st.Buzz != "strong" {
		t.Fatalf("set: %d %+v", code, st)
	}
	sam.do("PUT", "/api/bus/stop", map[string]any{"name": "Home stop", "lat": 34.7111, "lng": 137.7265, "alert_meters": 1200}, &st)
	if st.AlertMeters != 1200 {
		t.Errorf("alert distance: %+v", st)
	}
	sam.do("PUT", "/api/bus/stop", map[string]any{"name": "Home stop", "lat": 34.7111, "lng": 137.7265, "alert_meters": 1200, "buzz": "strongest"}, &st)
	if st.Buzz != "strongest" {
		t.Errorf("buzz: %+v", st)
	}
	// An older app, which doesn't know about buzz, keeps it as it was.
	sam.do("PUT", "/api/bus/stop", map[string]any{"name": "Home stop", "lat": 34.7111, "lng": 137.7265, "alert_meters": 600}, &st)
	if st.Buzz != "strongest" || st.AlertMeters != 600 {
		t.Errorf("buzz after an older app's save: %+v", st)
	}
	// Each person's own.
	var theirs struct{ Set bool }
	if fran.do("GET", "/api/bus/stop", nil, &theirs); theirs.Set {
		t.Error("fran sees sam's stop")
	}
}

type savedStop struct {
	ID          int64
	Name        string
	Lat, Lng    float64
	AlertMeters int `json:"alert_meters"`
	Kind        string
	Buzz        string
	SoundAfter  int `json:"sound_after"`
	Shifts      []string
	Ask         bool
}

type savedStops struct {
	Stops  []savedStop
	LastID int64 `json:"last_id"`
	Buzz   string
}

func (s savedStops) named(name string) savedStop {
	for _, x := range s.Stops {
		if x.Name == name {
			return x
		}
	}
	return savedStop{}
}

func TestSavedStops(t *testing.T) {
	ts := newTestServer(t)
	sam, fran := newClient(t, ts), newClient(t, ts)
	sam.signup("sam@example.com")
	fran.signup("fran@example.com")
	var all savedStops
	if sam.do("GET", "/api/bus/stops", nil, &all); len(all.Stops) != 0 || all.LastID != 0 || all.Buzz != "strong" {
		t.Fatalf("before: %+v", all)
	}
	// No stop to keep the buzz with.
	if code := sam.do("PUT", "/api/bus/buzz", map[string]any{"buzz": "gentle"}, nil); code != 400 {
		t.Errorf("buzz without a stop: %d", code)
	}
	for _, bad := range []map[string]any{
		{"name": "x"},
		{"name": "x", "lat": 35.0, "lng": 137.0, "alert_meters": 50},
		{"name": "x", "lat": 35.0, "lng": 137.0, "shifts": []string{"Lunch"}},
		{"name": "x", "lat": 35.0, "lng": 137.0, "kind": "ferry"},
	} {
		if code := sam.do("POST", "/api/bus/stops", bad, nil); code != 400 {
			t.Errorf("%v: %d", bad, code)
		}
	}

	// The first stop is the one used last.
	if code := sam.do("POST", "/api/bus/stops", map[string]any{"name": " Aoi ", "lat": 34.97, "lng": 138.38, "shifts": []string{"Late", "Early"}}, &all); code != 201 ||
		len(all.Stops) != 1 || all.Stops[0].Name != "Aoi" || all.LastID != all.Stops[0].ID || all.Stops[0].AlertMeters != 800 ||
		len(all.Stops[0].Shifts) != 2 || all.Stops[0].Shifts[0] != "Early" || all.Stops[0].Kind != "bus" {
		t.Fatalf("first: %d %+v", code, all)
	}
	aoi := all.Stops[0].ID
	// A second takes Early from the first, and isn't the one used last.
	sam.do("POST", "/api/bus/stops", map[string]any{"name": "Station", "lat": 34.97, "lng": 138.39, "alert_meters": 600, "shifts": []string{"Early", "Middle"}, "kind": "train"}, &all)
	station := all.named("Station")
	if len(all.Stops) != 2 || station.Kind != "train" || all.Stops[0].ID != aoi || all.LastID != aoi ||
		len(all.named("Aoi").Shifts) != 1 || all.named("Aoi").Shifts[0] != "Late" || len(station.Shifts) != 2 {
		t.Fatalf("second: %+v", all)
	}
	sam.do("POST", "/api/bus/stops", map[string]any{"name": "Mum's", "lat": 34.9, "lng": 138.3}, &all)
	if mum := all.named("Mum's"); mum.Shifts == nil || len(mum.Shifts) != 0 {
		t.Errorf("no shifts is []: %+v", mum)
	}

	// Changing a stop without shifts keeps them; with them, it takes them.
	sam.do("PUT", fmt.Sprintf("/api/bus/stops/%d", station.ID), map[string]any{"name": "Shizuoka Station", "lat": 34.97, "lng": 138.39, "alert_meters": 1000}, &all)
	if s := all.named("Shizuoka Station"); s.AlertMeters != 1000 || len(s.Shifts) != 2 || s.Kind != "train" {
		t.Errorf("changed: %+v", s)
	}
	// Each stop buzzes its own way: strong to start with, kept by a change that leaves it out.
	if all.named("Aoi").Buzz != "strong" || all.named("Shizuoka Station").Buzz != "strong" || all.named("Aoi").SoundAfter != 60 {
		t.Errorf("buzz and sound to start with: %+v", all)
	}
	// So is the sound: off at one stop, kept by a change that leaves it out.
	sam.do("PUT", fmt.Sprintf("/api/bus/stops/%d", aoi), map[string]any{"name": "Aoi", "lat": 34.97, "lng": 138.38, "sound_after": 0}, &all)
	sam.do("PUT", fmt.Sprintf("/api/bus/stops/%d", aoi), map[string]any{"name": "Aoi", "lat": 34.97, "lng": 138.38}, &all)
	if all.named("Aoi").SoundAfter != 0 || all.named("Shizuoka Station").SoundAfter != 60 {
		t.Errorf("one stop's sound: %+v", all)
	}
	if code := sam.do("PUT", fmt.Sprintf("/api/bus/stops/%d", aoi), map[string]any{"name": "Aoi", "lat": 34.97, "lng": 138.38, "sound_after": 45}, nil); code != 400 {
		t.Errorf("a stop's sound must be one of them: %d", code)
	}
	sam.do("PUT", fmt.Sprintf("/api/bus/stops/%d", station.ID), map[string]any{"name": "Shizuoka Station", "lat": 34.97, "lng": 138.39, "alert_meters": 1000, "buzz": "strongest"}, &all)
	if all.named("Shizuoka Station").Buzz != "strongest" || all.named("Aoi").Buzz != "strong" {
		t.Errorf("one stop's buzz: %+v", all)
	}
	if code := sam.do("PUT", fmt.Sprintf("/api/bus/stops/%d", station.ID), map[string]any{"name": "x", "lat": 34.97, "lng": 138.39, "buzz": "loud"}, nil); code != 400 {
		t.Errorf("a stop's buzz must be one of them: %d", code)
	}
	sam.do("PUT", fmt.Sprintf("/api/bus/stops/%d", station.ID), map[string]any{"name": "Shizuoka Station", "lat": 34.97, "lng": 138.39, "shifts": []string{"Late"}}, &all)
	if a, s := all.named("Aoi"), all.named("Shizuoka Station"); len(a.Shifts) != 0 || len(s.Shifts) != 1 || s.Shifts[0] != "Late" {
		t.Errorf("taking Late: %+v %+v", a, s)
	}

	// The question at the end of a shift: off for a new stop, switched on for
	// one, and kept by a change that leaves it out.
	if all.named("Aoi").Ask {
		t.Errorf("a new stop doesn't ask: %+v", all.named("Aoi"))
	}
	sam.do("PUT", fmt.Sprintf("/api/bus/stops/%d", aoi), map[string]any{"name": "Aoi", "lat": 34.97, "lng": 138.38, "ask": true}, &all)
	if !all.named("Aoi").Ask || all.named("Shizuoka Station").Ask {
		t.Errorf("asking: %+v", all)
	}
	sam.do("PUT", fmt.Sprintf("/api/bus/stops/%d", aoi), map[string]any{"name": "Aoi", "lat": 34.97, "lng": 138.38, "alert_meters": 900}, &all)
	if !all.named("Aoi").Ask || all.named("Aoi").AlertMeters != 900 {
		t.Errorf("kept: %+v", all.named("Aoi"))
	}
	sam.do("POST", "/api/bus/stops", map[string]any{"name": "Gym", "lat": 34.9, "lng": 138.3, "ask": true}, &all)
	if !all.named("Gym").Ask {
		t.Errorf("a new stop that asks: %+v", all.named("Gym"))
	}
	sam.do("DELETE", fmt.Sprintf("/api/bus/stops/%d", all.named("Gym").ID), nil, &all)

	// Starting a trip makes it the one used last, which older apps see.
	sam.do("POST", fmt.Sprintf("/api/bus/stops/%d/use", station.ID), nil, &all)
	var old struct {
		Set         bool
		Name        string
		AlertMeters int `json:"alert_meters"`
		Buzz        string
	}
	if sam.do("GET", "/api/bus/stop", nil, &old); all.LastID != station.ID || !old.Set || old.Name != "Shizuoka Station" || old.AlertMeters != 800 {
		t.Errorf("used: %+v %+v", all, old)
	}
	// An older app changes that one, and the buzz.
	sam.do("PUT", "/api/bus/stop", map[string]any{"name": "Shizuoka Stn", "lat": 34.97, "lng": 138.39, "alert_meters": 1200, "buzz": "strongest"}, nil)
	if sam.do("GET", "/api/bus/stops", nil, &all); len(all.Stops) != 3 || all.named("Shizuoka Stn").AlertMeters != 1200 || all.Buzz != "strongest" ||
		len(all.named("Shizuoka Stn").Shifts) != 1 {
		t.Errorf("after an older app's save: %+v", all)
	}
	if sam.do("PUT", "/api/bus/buzz", map[string]any{"buzz": "gentle"}, &all); all.Buzz != "gentle" {
		t.Errorf("buzz: %+v", all)
	}
	// As older apps set it: every stop's.
	for _, x := range all.Stops {
		if x.Buzz != "gentle" {
			t.Errorf("an older app's buzz is every stop's: %+v", x)
		}
	}
	if code := sam.do("PUT", "/api/bus/buzz", map[string]any{}, nil); code != 400 {
		t.Errorf("no buzz: %d", code)
	}

	// Each person's own: another's stop is a 404.
	if code := fran.do("PUT", fmt.Sprintf("/api/bus/stops/%d", aoi), map[string]any{"name": "x", "lat": 1.0, "lng": 1.0}, nil); code != 404 {
		t.Errorf("fran changing sam's stop: %d", code)
	}
	if code := fran.do("DELETE", fmt.Sprintf("/api/bus/stops/%d", aoi), nil, nil); code != 404 {
		t.Errorf("fran removing sam's stop: %d", code)
	}
	var theirs savedStops
	if fran.do("GET", "/api/bus/stops", nil, &theirs); len(theirs.Stops) != 0 {
		t.Error("fran sees sam's stops")
	}

	// Removing the one used last: another is.
	sam.do("DELETE", fmt.Sprintf("/api/bus/stops/%d", station.ID), nil, &all)
	if len(all.Stops) != 2 || all.LastID == station.ID || all.LastID == 0 {
		t.Errorf("removed: %+v", all)
	}
	if code := sam.do("DELETE", fmt.Sprintf("/api/bus/stops/%d", station.ID), nil, nil); code != 404 {
		t.Errorf("removed twice: %d", code)
	}
	for _, x := range all.Stops {
		sam.do("DELETE", fmt.Sprintf("/api/bus/stops/%d", x.ID), nil, &all)
	}
	if sam.do("GET", "/api/bus/stop", nil, &old); old.Set || len(all.Stops) != 0 || all.LastID != 0 {
		t.Errorf("none left: %+v %+v", all, old)
	}
}
