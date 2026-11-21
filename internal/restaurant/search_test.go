package restaurant

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestSearch(t *testing.T) {
	var calls atomic.Int32
	var lastQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		lastQuery = r.URL.RawQuery
		// One place mapped as a point and a building, another branch, and one without a name.
		w.Write([]byte(`{"features":[
			{"geometry":{"coordinates":[137.7340,34.7040]},"properties":{"name":"一蘭 浜松店","osm_value":"restaurant","city":"浜松市","district":"中央区","street":"鍛冶町"}},
			{"geometry":{"coordinates":[137.7341,34.7041]},"properties":{"name":"一蘭 浜松店","osm_value":"restaurant","city":"浜松市"}},
			{"geometry":{"coordinates":[138.38,34.97]},"properties":{"name":"一蘭 静岡店","osm_value":"restaurant","state":"静岡県","locality":"紺屋町"}},
			{"geometry":{"coordinates":[139.71,35.73]},"properties":{"name":"一蘭 池袋店","osm_value":"restaurant","city":"豊島区","district":"豊島区"}},
			{"geometry":{"coordinates":[137.0,34.0]},"properties":{"osm_value":"cafe"}},
			{"geometry":{"coordinates":[137.72,34.71]},"properties":{"name":"一蘭公園","osm_key":"leisure","osm_value":"park","city":"浜松市"}}
		]}`))
	}))
	defer srv.Close()
	s := &placeSearch{base: srv.URL, client: srv.Client()}

	found, err := s.search(context.Background(), "一蘭", &[2]float64{34.70, 137.73})
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 4 {
		t.Fatalf("got %+v", found)
	}
	if f := found[0]; f.Name != "一蘭 浜松店" || f.Area != "浜松市 中央区" || f.Street != "鍛冶町" || f.Kind != "restaurant" || f.Lat != 34.7040 || f.Source != SourceOSM {
		t.Errorf("first: %+v", f)
	}
	// How far each is from where the search was asked from: 一蘭 浜松店 is about 500 m away.
	if m := found[0].Meters; m == nil || *m < 400 || *m > 600 {
		t.Errorf("first is %v m away", m)
	}
	if f := found[1]; f.Area != "静岡県" || f.Street != "紺屋町" {
		t.Errorf("second: %+v", f)
	}
	if f := found[2]; f.Area != "豊島区" {
		t.Errorf("ward: %+v", f)
	}
	if f := found[3]; f.Kind != "park" || f.Category != CategoryNature {
		t.Errorf("park: %+v", f)
	}
	for _, want := range []string{"lat=34.7000", "location_bias_scale=0", "osm_tag=amenity%3Arestaurant", "osm_tag=amenity%3Acafe",
		"osm_tag=leisure%3Apark", "osm_tag=historic&", "osm_tag=amenity%3Apublic_bath"} {
		if !strings.Contains(lastQuery, want) {
			t.Errorf("query %q lacks %q", lastQuery, want)
		}
	}
	// Asked again nearby: from the cache, with the distance from there.
	again, err := s.search(context.Background(), "一蘭", &[2]float64{34.7040, 137.7340})
	if err != nil || calls.Load() != 1 {
		t.Errorf("calls = %d, %v", calls.Load(), err)
	}
	if m := again[0].Meters; m == nil || *m != 0 {
		t.Errorf("from the place itself: %v m", m)
	}
	if m := found[0].Meters; *m < 400 {
		t.Errorf("the cached answer changed: %d m", *m)
	}
	// Without a position: no distance.
	if plain, _ := s.search(context.Background(), "一蘭", nil); len(plain) == 0 || plain[0].Meters != nil {
		t.Errorf("without a position: %+v", plain)
	}
}
