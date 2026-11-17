package server_test

import "testing"

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
