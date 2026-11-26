package server_test

import "testing"

type activityJSON struct {
	Who     string
	Me      bool
	Via     string
	Kind    string
	Details map[string]any
}

func TestActivity(t *testing.T) {
	ts := newTestServer(t, closed)
	fran, sam := newClient(t, ts), newClient(t, ts)
	fran.signup("fran@example.com")
	fran.do("PUT", "/api/space/me", map[string]string{"name": "Fran"}, nil)
	fran.do("POST", "/api/space/invites", map[string]string{"email": "sam@example.com"}, nil)
	sam.signup("sam@example.com")
	sam.do("PUT", "/api/space/me", map[string]string{"name": "Sam"}, nil)

	sam.do("PUT", "/api/shifts/settings", map[string]any{"person_name": "Sam", "lengths": map[string]float64{"F": 10}}, nil)
	sam.do("PUT", "/api/shifts/month", map[string]any{"month": "2026-11", "days": []map[string]string{{"date": "2026-11-02", "code": "13F"}}}, nil)
	var r struct{ ID int64 }
	sam.do("POST", "/api/restaurants", map[string]any{"name": "Taco Loco", "visit": map[string]string{"visited_on": "2026-11-01"}, "stars": 4}, &r)
	fran.do("PUT", "/api/restaurants/"+itoa(r.ID)+"/rating", map[string]int{"stars": 5}, nil)
	var stops struct{ Stops []struct{ ID int64 } }
	sam.do("POST", "/api/bus/stops", map[string]any{"name": "Aoi", "lat": 34.98, "lng": 138.38}, &stops)
	sam.do("POST", "/api/bus/stops/"+itoa(stops.Stops[0].ID)+"/use", nil, nil)
	// Through Claude.
	token := connect(t, ts.URL, fran)
	tool(t, ts.URL, token, "set_shift_lengths", map[string]any{"lengths": map[string]float64{"F": 9}})

	var list []activityJSON
	if code := sam.do("GET", "/api/activity", nil, &list); code != 200 {
		t.Fatalf("list: %d", code)
	}
	kinds := []string{}
	for _, e := range list {
		kinds = append(kinds, e.Kind)
	}
	want := []string{"shift_settings", "rated", "restaurant_added", "shifts_saved", "shift_settings", "invited"}
	if len(list) != len(want) {
		t.Fatalf("kinds %v, want %v", kinds, want)
	}
	for i, k := range want {
		if list[i].Kind != k {
			t.Fatalf("kinds %v, want %v", kinds, want)
		}
	}
	if e := list[0]; e.Who != "Fran" || e.Me || e.Via != "claude" {
		t.Errorf("via claude: %+v", e)
	}
	if f := list[0].Details["lengths"].(map[string]any)["F"].([]any); f[0] != 10.0 || f[1] != 9.0 {
		t.Errorf("F change: %v", list[0].Details)
	}
	if e := list[4]; e.Who != "Sam" || !e.Me || e.Via != "app" || e.Details["person_name"] == nil {
		t.Errorf("settings by sam: %+v", e)
	}
	if e := list[1]; e.Details["name"] != "Taco Loco" || e.Details["stars"] != 5.0 {
		t.Errorf("rating: %+v", e)
	}
	// The bus alarm started isn't listed, but the phone's notification check gets it.
	var withBus []activityJSON
	if fran.do("GET", "/api/activity?bus=1", nil, &withBus); len(withBus) != len(want)+1 || withBus[1].Kind != "bus_started" || withBus[1].Who != "Sam" || withBus[1].Details["name"] != "Aoi" {
		t.Errorf("with bus: %+v", withBus)
	}
	// Another space sees none of it.
	other := newClient(t, newTestServer(t))
	other.signup("x@example.com")
	if other.do("GET", "/api/activity", nil, &list); len(list) != 0 {
		t.Errorf("other space: %+v", list)
	}
}

// Each change tells the other person's phones at once, and not the phones of
// whoever made it.
func TestActivityPushesToTheOther(t *testing.T) {
	sam, fran, fake, _ := lockPair(t)

	var r struct{ ID int64 }
	if code := sam.do("POST", "/api/restaurants", map[string]any{"name": "Taco Loco"}, &r); code != 201 {
		t.Fatalf("add: %d", code)
	}
	got := fake.waitFor(t, "fran-phone", "activity")
	if got["kind"] != "restaurant_added" || got["id"] == "" {
		t.Errorf("push %v", got)
	}
	var list []struct {
		ID   int64
		Kind string
	}
	fran.do("GET", "/api/activity?bus=1", nil, &list)
	if len(list) == 0 || itoa(list[0].ID) != got["id"] {
		t.Errorf("push id %s, newest %+v", got["id"], list)
	}

	fran.do("PUT", "/api/restaurants/"+itoa(r.ID)+"/rating", map[string]int{"stars": 5}, nil)
	if got := fake.waitFor(t, "sam-phone", "activity"); got["kind"] != "rated" {
		t.Errorf("push to sam %v", got)
	}
	fake.mu.Lock()
	defer fake.mu.Unlock()
	for _, p := range fake.sent {
		if p.data["type"] == "activity" {
			t.Errorf("extra push %+v (to whoever made the change?)", p)
		}
	}
}
