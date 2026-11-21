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

// A common name and its town: that town's stops come first, and the others
// of the name elsewhere don't crowd them out.
func TestSearchInTown(t *testing.T) {
	var queries []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		queries = append(queries, q.Get("q"))
		switch {
		case q.Get("layer") != "" && (q.Get("q") == "知立" || q.Get("q") == "chiryu"):
			// The town: Chiryu, Aichi.
			w.Write([]byte(`{"features":[{"geometry":{"coordinates":[137.0503,35.0016]},"properties":{"name":"知立市","osm_key":"place"}}]}`))
		case q.Get("layer") != "":
			w.Write([]byte(`{"features":[]}`))
		case q.Get("q") == "新林" && q.Get("lat") == "35.0016":
			// Near Chiryu, Photon still puts a far one first.
			w.Write([]byte(`{"features":[
				{"geometry":{"coordinates":[139.60,35.70]},"properties":{"name":"新林","osm_key":"highway","city":"東京都"}},
				{"geometry":{"coordinates":[137.0581,35.0102]},"properties":{"name":"新林","osm_key":"highway","city":"知立市"}},
				{"geometry":{"coordinates":[137.0420,34.9950]},"properties":{"name":"新林町","osm_key":"highway","city":"知立市"}}
			]}`))
		default:
			// The whole text, and the name alone: other towns' stops of that name.
			w.Write([]byte(`{"features":[
				{"geometry":{"coordinates":[139.60,35.70]},"properties":{"name":"新林","osm_key":"highway","city":"東京都"}},
				{"geometry":{"coordinates":[135.70,34.98]},"properties":{"name":"新林","osm_key":"highway","city":"京都市"}}
			]}`))
		}
	}))
	defer srv.Close()

	for _, q := range []string{"新林 知立", "新林　知立", "知立 新林"} {
		s := &Searcher{base: srv.URL, client: srv.Client()}
		queries = nil
		found, err := s.Search(context.Background(), q, nil)
		if err != nil {
			t.Fatal(err)
		}
		var got []string
		for _, f := range found {
			got = append(got, f.Name+" "+f.Area)
		}
		want := []string{"新林 知立市", "新林町 知立市", "新林 東京都", "新林 京都市"}
		if strings.Join(got, ",") != strings.Join(want, ",") {
			t.Errorf("%s: got %q, want %q (asked %q)", q, got, want, queries)
		}
	}

	// One word: no town to look for.
	s := &Searcher{base: srv.URL, client: srv.Client()}
	queries = nil
	if found, err := s.Search(context.Background(), "新林", nil); err != nil || len(found) != 2 || len(queries) != 1 {
		t.Errorf("one word: %+v %v, asked %q", found, err, queries)
	}
}
