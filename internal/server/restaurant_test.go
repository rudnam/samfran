package server_test

import (
	"strconv"
	"strings"
	"testing"
	"time"
)

type restaurantJSON struct {
	ID         int64    `json:"id"`
	Name       string   `json:"name"`
	Area       string   `json:"area"`
	Category   string   `json:"category"`
	Lat        *float64 `json:"lat"`
	Lng        *float64 `json:"lng"`
	MapURL     string   `json:"map_url"`
	Notes      string   `json:"notes"`
	VisitCount int      `json:"visit_count"`
	LastVisit  *string  `json:"last_visit"`
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
	VisitNotes []struct {
		VisitedOn string `json:"visited_on"`
		Note      string `json:"note"`
	} `json:"visit_notes"`
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
		len(r.Ratings) != 1 || r.Ratings[0].Stars != 4 || !r.Ratings[0].Me || len(r.Visits) != 1 || r.Visits[0].Note != "great salsa" ||
		r.Notes != "2026-10-01 · great salsa" { // what's written about a visit shows in the place's notes
		t.Fatalf("create: %d %+v", code, r)
	}
	path := "/api/restaurants/" + itoa(r.ID)

	// One to go to: no visits.
	var want restaurantJSON
	alice.do("POST", "/api/restaurants", map[string]string{"name": "Blue Sky Café"}, &want)
	if want.VisitCount != 0 || want.LastVisit != nil || len(want.Ratings) != 0 || want.Category != "eat" {
		t.Errorf("want-to-go: %+v", want)
	}

	// Any place to go: a park, in its category. An edit that leaves the
	// category out (an older app's) keeps it.
	var park restaurantJSON
	if code := alice.do("POST", "/api/restaurants", map[string]string{"name": "Castle Park", "category": "nature"}, &park); code != 201 || park.Category != "nature" {
		t.Fatalf("park: %d %+v", code, park)
	}
	parkPath := "/api/restaurants/" + itoa(park.ID)
	if alice.do("PATCH", parkPath, map[string]string{"notes": "cherry trees"}, &park); park.Category != "nature" || park.Notes != "cherry trees" {
		t.Errorf("patch without a category: %+v", park)
	}
	if alice.do("PATCH", parkPath, map[string]string{"category": "culture"}, &park); park.Category != "culture" {
		t.Errorf("change the category: %+v", park)
	}
	if park.Lat != nil {
		t.Errorf("a park with no position has one: %+v", park)
	}

	// A position: given when it's picked from the search; kept by other
	// edits, replaced by a new one, cleared by a new map link.
	alice.do("PATCH", parkPath, map[string]any{"lat": 34.711, "lng": 137.725}, &park)
	if park.Lat == nil || *park.Lat != 34.711 || *park.Lng != 137.725 {
		t.Fatalf("set position: %+v", park)
	}
	if alice.do("PATCH", parkPath, map[string]string{"notes": "sakura"}, &park); park.Lat == nil {
		t.Errorf("an edit dropped the position: %+v", park)
	}
	if alice.do("PATCH", parkPath, map[string]string{"map_url": "https://maps.app.goo.gl/other"}, &park); park.Lat != nil {
		t.Errorf("a new link kept the old position: %+v", park)
	}
	if code := alice.do("PATCH", parkPath, map[string]any{"lat": 34.7}, nil); code != 400 {
		t.Errorf("lat without lng: %d, want 400", code)
	}
	var placed restaurantJSON
	alice.do("POST", "/api/restaurants", map[string]any{"name": "Dunes", "category": "nature", "lat": 34.656, "lng": 137.731}, &placed)
	if placed.Lat == nil || *placed.Lng != 137.731 {
		t.Errorf("created with a position: %+v", placed)
	}
	alice.do("DELETE", "/api/restaurants/"+itoa(placed.ID), nil, nil)
	var mapKey struct{ Key *string }
	if code := alice.do("GET", "/api/restaurants/map", nil, &mapKey); code != 200 || mapKey.Key == nil || *mapKey.Key != "" {
		t.Errorf("map key without one: %d %+v", code, mapKey)
	}

	if code := alice.do("DELETE", parkPath, nil, nil); code != 204 {
		t.Fatalf("delete park: %d", code)
	}

	// Another visit, newest first.
	if code := alice.do("POST", path+"/visits", map[string]string{"visited_on": "2026-11-02", "note": "tantanmen"}, &r); code != 201 ||
		r.VisitCount != 2 || *r.LastVisit != "2026-11-02" || r.Visits[0].VisitedOn != "2026-11-02" ||
		r.Notes != "2026-10-01 · great salsa\n2026-11-02 · tantanmen" {
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
	// The list carries what was written about visits, for searching; a place without notes has none.
	if n := list[0].VisitNotes; len(n) != 1 || n[0].Note != "tantanmen" || n[0].VisitedOn != "2026-11-02" || len(list[1].VisitNotes) != 0 {
		t.Errorf("visit notes in the list: %+v", list)
	}

	// The apps show one date: PUT .../been moves the newest visit, and the
	// older ones (as older apps logged them) stay.
	alice.do("POST", path+"/visits", map[string]string{"visited_on": "2026-11-05"}, &r)
	if code := alice.do("PUT", path+"/been", map[string]string{"visited_on": "2026-11-08"}, &r); code != 200 ||
		r.VisitCount != 2 || *r.LastVisit != "2026-11-08" || r.Visits[0].VisitedOn != "2026-11-08" || r.Visits[1].Note != "tantanmen" {
		t.Fatalf("set been date: %d %+v", code, r)
	}
	for _, body := range []any{map[string]string{"visited_on": "yesterday"}, map[string]any{}} {
		if code := alice.do("PUT", path+"/been", body, nil); code != 400 {
			t.Errorf("bad been date %v: %d", body, code)
		}
	}
	// Not been yet: back on the want-to-go list.
	if code := alice.do("DELETE", path+"/been", nil, &r); code != 200 || r.VisitCount != 0 || r.LastVisit != nil {
		t.Fatalf("not been yet: %d %+v", code, r)
	}
	// Been again: a first visit.
	if code := alice.do("PUT", path+"/been", map[string]string{"visited_on": "2026-11-01"}, &r); code != 200 || r.VisitCount != 1 || *r.LastVisit != "2026-11-01" {
		t.Fatalf("been again: %d %+v", code, r)
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
		{"PUT", path + "/been", map[string]string{"visited_on": "2026-11-01"}},
		{"DELETE", path + "/been", nil},
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
		{"name": "x", "category": "food"},
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
	if code := c.do("PATCH", "/api/restaurants/"+itoa(r.ID), map[string]string{"category": "parks"}, nil); code != 400 {
		t.Errorf("patch to an unknown category: %d, want 400", code)
	}
	if code := c.do("GET", "/api/restaurants/abc", nil, nil); code != 404 {
		t.Errorf("bad id: %d, want 404", code)
	}
}

func TestRestaurantComments(t *testing.T) {
	ts := newTestServer(t)
	fran, sam, other := newClient(t, ts), newClient(t, ts), newClient(t, ts)
	fran.signup("fran@example.com")
	fran.do("POST", "/api/space/invites", map[string]string{"email": "sam@example.com"}, nil)
	sam.signup("sam@example.com") // joins fran's space
	other.signup("other@example.com")

	type commentsJSON struct {
		Comments []struct {
			ID   int64  `json:"id"`
			Body string `json:"body"`
			By   string `json:"by"`
			Me   bool   `json:"me"`
		} `json:"comments"`
	}
	var place restaurantJSON
	fran.do("POST", "/api/restaurants", map[string]string{"name": "Kaze Ramen"}, &place)
	path := "/api/restaurants/" + itoa(place.ID)

	// Not been yet: nothing to comment on.
	if code := fran.do("POST", path+"/comments", map[string]string{"body": "can't wait"}, nil); code != 409 {
		t.Errorf("comment before been: %d, want 409", code)
	}
	fran.do("PUT", path+"/been", map[string]string{"visited_on": "2026-11-01"}, nil)
	for _, body := range []string{"", "   ", strings.Repeat("あ", 1001)} {
		if code := fran.do("POST", path+"/comments", map[string]string{"body": body}, nil); code != 400 {
			t.Errorf("comment %q: %d, want 400", body, code)
		}
	}

	var c commentsJSON
	if code := fran.do("POST", path+"/comments", map[string]string{"body": " The broth was perfect "}, &c); code != 201 ||
		len(c.Comments) != 1 || c.Comments[0].Body != "The broth was perfect" || !c.Comments[0].Me {
		t.Fatalf("fran comments: %d %+v", code, c)
	}
	if code := sam.do("POST", path+"/comments", map[string]string{"body": "Too salty for me"}, &c); code != 201 ||
		len(c.Comments) != 2 || c.Comments[0].Me || c.Comments[0].By != "fran" || !c.Comments[1].Me || c.Comments[1].By != "sam" {
		t.Fatalf("sam comments: %d %+v", code, c)
	}
	franID, samID := c.Comments[0].ID, c.Comments[1].ID

	// Only the writer can delete a comment.
	if code := sam.do("DELETE", path+"/comments/"+itoa(franID), nil, nil); code != 404 {
		t.Errorf("sam deletes fran's comment: %d, want 404", code)
	}
	// Another space sees and touches none of it.
	if code := other.do("POST", path+"/comments", map[string]string{"body": "hi"}, nil); code != 404 {
		t.Errorf("other space comments: %d, want 404", code)
	}
	if code := other.do("DELETE", path+"/comments/"+itoa(samID), nil, nil); code != 404 {
		t.Errorf("other space deletes: %d, want 404", code)
	}
	if code := sam.do("DELETE", path+"/comments/"+itoa(samID), nil, &c); code != 200 || len(c.Comments) != 1 || c.Comments[0].ID != franID {
		t.Fatalf("sam deletes own: %d %+v", code, c)
	}

	// The list doesn't carry them; going back to want to go keeps them.
	var list []commentsJSON
	if fran.do("GET", "/api/restaurants", nil, &list); len(list) != 1 || len(list[0].Comments) != 0 {
		t.Errorf("list carries comments: %+v", list)
	}
	fran.do("DELETE", path+"/been", nil, nil)
	if fran.do("GET", path, nil, &c); len(c.Comments) != 1 {
		t.Errorf("comments after not been: %+v", c)
	}
}
