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
			{"geometry":{"coordinates":[137.0,34.0]},"properties":{"osm_value":"cafe"}}
		]}`))
	}))
	defer srv.Close()
	s := &placeSearch{base: srv.URL, client: srv.Client()}

	found, err := s.search(context.Background(), "一蘭", &[2]float64{34.70, 137.73})
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 3 {
		t.Fatalf("got %+v", found)
	}
	if f := found[0]; f.Name != "一蘭 浜松店" || f.Area != "浜松市 中央区" || f.Street != "鍛冶町" || f.Kind != "restaurant" || f.Lat != 34.7040 {
		t.Errorf("first: %+v", f)
	}
	if f := found[1]; f.Area != "静岡県" || f.Street != "紺屋町" {
		t.Errorf("second: %+v", f)
	}
	if f := found[2]; f.Area != "豊島区" {
		t.Errorf("ward: %+v", f)
	}
	for _, want := range []string{"lat=34.7000", "location_bias_scale=0", "osm_tag=amenity%3Arestaurant", "osm_tag=amenity%3Acafe"} {
		if !strings.Contains(lastQuery, want) {
			t.Errorf("query %q lacks %q", lastQuery, want)
		}
	}
	// Asked again nearby: from the cache.
	if _, err := s.search(context.Background(), "一蘭", &[2]float64{34.7001, 137.7301}); err != nil || calls.Load() != 1 {
		t.Errorf("calls = %d, %v", calls.Load(), err)
	}
}
