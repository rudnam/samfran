package bus

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
		// As Photon answered for 葵西: the stop on each side of the road, and a station.
		w.Write([]byte(`{"features":[
			{"geometry":{"coordinates":[137.71207,34.76966]},"properties":{"name":"葵西小学校入口","osm_key":"highway","city":"浜松市","district":"中央区","street":"磐田細江線"}},
			{"geometry":{"coordinates":[137.71239,34.76952]},"properties":{"name":"葵西小学校入口","osm_key":"highway","city":"浜松市","district":"中央区"}},
			{"geometry":{"coordinates":[137.74533,34.74164]},"properties":{"name":"上島","osm_key":"railway","city":"浜松市"}},
			{"geometry":{"coordinates":[137.0,34.0]},"properties":{"osm_key":"highway"}}
		]}`))
	}))
	defer srv.Close()
	s := &Searcher{base: srv.URL, client: srv.Client()}

	found, err := s.Search(context.Background(), "葵西", &[2]float64{34.77, 137.71})
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 2 {
		t.Fatalf("got %+v", found)
	}
	if f := found[0]; f.Name != "葵西小学校入口" || f.Kind != "bus" || f.Area != "浜松市 中央区 磐田細江線" || f.Lat != 34.76966 {
		t.Errorf("stop: %+v", f)
	}
	if found[1].Kind != "train" {
		t.Errorf("station: %+v", found[1])
	}
	if want := "lat=34.7700"; !strings.Contains(lastQuery, want) {
		t.Errorf("query %q lacks %q", lastQuery, want)
	}
	// Asked again nearby: from the cache.
	if _, err := s.Search(context.Background(), "葵西", &[2]float64{34.7701, 137.7101}); err != nil || calls.Load() != 1 {
		t.Errorf("calls = %d, %v", calls.Load(), err)
	}
}
