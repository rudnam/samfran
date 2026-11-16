package restaurant

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGeocoder(t *testing.T) {
	answers := map[string]string{
		// Two stops, one each side of the road, and a school of nearly the same name.
		"葵西小学校入口": `[{"lat":"34.7695178","lon":"137.7123915","type":"bus_stop","name":"葵西小学校入口"},
			{"lat":"34.7696621","lon":"137.7120673","type":"bus_stop","name":"葵西小学校入口"},
			{"lat":"34.77","lon":"137.71","type":"school","name":"葵西小学校"}]`,
		// The same name in two towns.
		"駅前":   `[{"lat":"34.7","lon":"137.7","type":"bus_stop","name":"駅前"},{"lat":"35.6","lon":"139.7","type":"bus_stop","name":"駅前"}]`,
		"Cafe": `[{"lat":"35.0","lon":"137.0","type":"cafe","name":"Cafe"}]`,
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if a, ok := answers[r.URL.Query().Get("q")]; ok {
			w.Write([]byte(a))
			return
		}
		w.Write([]byte(`[]`))
	}))
	defer srv.Close()
	g := &geocoder{base: srv.URL, client: srv.Client()}
	ctx := context.Background()

	if lat, lng := g.find(ctx, "葵西小学校入口（バス）"); lat == nil || *lat < 34.7695 || *lat > 34.7697 || *lng < 137.712 || *lng > 137.7124 {
		t.Errorf("bus stop: %v %v", lat, lng)
	}
	if lat, _ := g.find(ctx, "駅前（バス）"); lat != nil {
		t.Errorf("ambiguous name found %v", *lat)
	}
	if lat, _ := g.find(ctx, "Cafe (Bus)"); lat != nil {
		t.Errorf("a cafe counted as a bus stop: %v", *lat)
	}
	if lat, _ := g.find(ctx, "Cafe"); lat == nil || *lat != 35 {
		t.Errorf("place: %v", lat)
	}
	if lat, _ := g.find(ctx, "Nowhere"); lat != nil {
		t.Errorf("unknown: %v", *lat)
	}
}
