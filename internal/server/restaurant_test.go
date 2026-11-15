package server_test

import (
	"strconv"
	"strings"
	"testing"
	"time"
)

type restaurantJSON struct {
	ID         int64   `json:"id"`
	Name       string  `json:"name"`
	Area       string  `json:"area"`
	MapURL     string  `json:"map_url"`
	Notes      string  `json:"notes"`
	VisitCount int     `json:"visit_count"`
	LastVisit  *string `json:"last_visit"`
	Ratings    []struct {
		Name  string `json:"name"`
		Me    bool   `json:"me"`
		Stars int    `json:"stars"`
	} `json:"ratings"`
	Visits []struct {
		ID        int64  `json:"id"`
		VisitedOn string `json:"visited_on"`
		Note      string `json:"note"`
	} `json:"visits"`
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func TestRestaurants(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com") // a separate space

	// Added with a first visit and a rating.
	var r restaurantJSON
	code := alice.do("POST", "/api/restaurants", map[string]any{
		"name": "  Taco Loco ", "area": "Station East",
		"visit": map[string]string{"visited_on": "2026-10-01", "note": "great salsa"}, "stars": 4,
	}, &r)
	if code != 201 || r.Name != "Taco Loco" || r.VisitCount != 1 || r.LastVisit == nil || *r.LastVisit != "2026-10-01" ||
		len(r.Ratings) != 1 || r.Ratings[0].Stars != 4 || !r.Ratings[0].Me || len(r.Visits) != 1 || r.Visits[0].Note != "great salsa" {
		t.Fatalf("create: %d %+v", code, r)
	}
	path := "/api/restaurants/" + itoa(r.ID)

	// One to go to: no visits.
	var want restaurantJSON
	alice.do("POST", "/api/restaurants", map[string]string{"name": "Blue Sky Café"}, &want)
	if want.VisitCount != 0 || want.LastVisit != nil || len(want.Ratings) != 0 {
		t.Errorf("want-to-go: %+v", want)
	}

	// Another visit, newest first.
	if code := alice.do("POST", path+"/visits", map[string]string{"visited_on": "2026-11-02"}, &r); code != 201 ||
		r.VisitCount != 2 || *r.LastVisit != "2026-11-02" || r.Visits[0].VisitedOn != "2026-11-02" {
		t.Fatalf("add visit: %d %+v", code, r)
	}
	if code := alice.do("DELETE", path+"/visits/"+itoa(r.Visits[1].ID), nil, &r); code != 200 || r.VisitCount != 1 {
		t.Fatalf("delete visit: %d %+v", code, r)
	}

	// Edit only some fields; the rest stay.
	if code := alice.do("PATCH", path, map[string]string{"notes": "ask for the green salsa"}, &r); code != 200 ||
		r.Notes != "ask for the green salsa" || r.Area != "Station East" {
		t.Fatalf("patch: %d %+v", code, r)
	}
	// Rating changes and clears.
	alice.do("PUT", path+"/rating", map[string]int{"stars": 5}, &r)
	if r.Ratings[0].Stars != 5 {
		t.Errorf("re-rate: %+v", r.Ratings)
	}
	alice.do("PUT", path+"/rating", map[string]int{"stars": 0}, &r)
	if len(r.Ratings) != 0 {
		t.Errorf("clear rating: %+v", r.Ratings)
	}

	var list []restaurantJSON
	if code := alice.do("GET", "/api/restaurants", nil, &list); code != 200 || len(list) != 2 {
		t.Fatalf("list: %d %+v", code, list)
	}

	// Bob's space can't see or touch any of it.
	if bob.do("GET", "/api/restaurants", nil, &list); len(list) != 0 {
		t.Errorf("bob sees alice's restaurants: %+v", list)
	}
	for _, c := range []struct {
		method, path string
		body         any
	}{
		{"GET", path, nil},
		{"PATCH", path, map[string]string{"name": "mine"}},
		{"POST", path + "/visits", map[string]string{"visited_on": "2026-11-01"}},
		{"PUT", path + "/rating", map[string]int{"stars": 1}},
		{"DELETE", path, nil},
	} {
		if code := bob.do(c.method, c.path, c.body, nil); code != 404 {
			t.Errorf("bob %s %s: %d, want 404", c.method, c.path, code)
		}
	}

	if code := alice.do("DELETE", path, nil, nil); code != 204 {
		t.Fatalf("delete: %d", code)
	}
	if code := alice.do("GET", path, nil, nil); code != 404 {
		t.Errorf("get deleted: %d", code)
	}
}

func TestRestaurantValidation(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	tomorrow2 := time.Now().AddDate(0, 0, 3).Format(time.DateOnly)
	for _, body := range []map[string]any{
		{"name": ""},
		{"name": "   "},
		{"name": strings.Repeat("x", 201)},
		{"name": "x", "map_url": "javascript:alert(1)"},
		{"name": "x", "map_url": "maps.google.com"},
		{"name": "x", "stars": 6},
		{"name": "x", "visit": map[string]string{"visited_on": "yesterday"}},
		{"name": "x", "visit": map[string]string{"visited_on": tomorrow2}},
		{"name": "x", "colour": "red"},
	} {
		if code := c.do("POST", "/api/restaurants", body, nil); code != 400 {
			t.Errorf("%v: %d, want 400", body, code)
		}
	}
	var r restaurantJSON
	if code := c.do("POST", "/api/restaurants", map[string]string{"name": "x", "map_url": "https://maps.app.goo.gl/abc"}, &r); code != 201 {
		t.Errorf("good map link: %d", code)
	}
	if code := c.do("PATCH", "/api/restaurants/"+itoa(r.ID), map[string]string{"name": ""}, nil); code != 400 {
		t.Errorf("patch to empty name: %d, want 400", code)
	}
	if code := c.do("GET", "/api/restaurants/abc", nil, nil); code != 404 {
		t.Errorf("bad id: %d, want 404", code)
	}
}
