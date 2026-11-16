package restaurant

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestMapsHost(t *testing.T) {
	for raw, want := range map[string]bool{
		"https://maps.app.goo.gl/AbC123":                      true,
		"https://goo.gl/maps/AbC123":                          true,
		"https://goo.gl/other":                                false,
		"https://www.google.com/maps/place/Taco+Loco/@35,137": true,
		"https://www.google.co.jp/maps/search/?api=1&q=x":     true,
		"https://maps.google.com/?q=Taco":                     true,
		"https://www.google.com/search?q=taco":                false,
		"https://google.com.evil.example/maps":                false,
		"https://evil.example/maps/place/x":                   false,
		"javascript:alert(1)":                                 false,
		"http://169.254.169.254/latest/meta-data":             false,
	} {
		u, _ := url.Parse(raw)
		if got := mapsHost(u); got != want {
			t.Errorf("mapsHost(%s) = %v, want %v", raw, got, want)
		}
	}
}

func TestPlaceName(t *testing.T) {
	for raw, want := range map[string]string{
		"https://www.google.com/maps/place/Taco+Loco/@35.7,137.7,17z/data=!3m1":          "Taco Loco",
		"https://www.google.com/maps/place/%E9%A4%83%E5%AD%90%E5%B0%8F%E7%94%BA/@35,137": "餃子小町",
		"https://maps.google.com/?q=Blue+Sky+Caf%C3%A9,+1-2-3+Riverside":                 "Blue Sky Café",
		"https://www.google.com/maps/search/?api=1&query=Ramen%20Tsubaki":                "Ramen Tsubaki",
		"https://www.google.com/maps?q=35.7,137.7":                                       "",
		"https://www.google.com/maps/@35.7,137.7,15z":                                    "",
	} {
		u, _ := url.Parse(raw)
		if got := placeName(u); got != want {
			t.Errorf("placeName(%s) = %q, want %q", raw, got, want)
		}
	}
}

// A short link is followed to the place page, but never off Google Maps.
func TestLookupFollowsOnlyMaps(t *testing.T) {
	var hits []string
	google := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits = append(hits, r.URL.Path)
		switch r.URL.Path {
		case "/short":
			w.Header().Set("Location", "https://www.google.com/maps/place/Hanabi+Grill/@35.0,137.0,17z")
		case "/elsewhere":
			w.Header().Set("Location", "https://evil.example/steal")
		case "/stop":
			// As maps.app.goo.gl/M3xa3caRE4zfzkH76 does: a place with no position in its address.
			w.Header().Set("Location", "https://www.google.com/maps/place/%E8%91%B5%E8%A5%BF%E5%B0%8F%E5%AD%A6%E6%A0%A1%E5%85%A5%E5%8F%A3%EF%BC%88%E3%83%90%E3%82%B9%EF%BC%89/data=!4m2!3m1!1s0x601b209b09db8355:0xc0dbcebdcde7c6a0!18m1!1e1?entry=gps")
		default:
			if strings.HasPrefix(r.URL.Path, "/maps/place/") {
				w.Write([]byte(`<html>`)) // Google's page: no position without its scripts
				return
			}
		}
		w.WriteHeader(http.StatusFound)
	}))
	defer google.Close()
	// Send every request to the fake, whatever its host.
	h := &Handler{maps: &http.Client{CheckRedirect: noRedirects, Transport: rewrite(google.URL)}}

	if p, err := h.lookup(context.Background(), "https://maps.app.goo.gl/short"); err != nil || p.Name != "Hanabi Grill" || p.Lat == nil || *p.Lat != 35 {
		t.Errorf("short link: %+v %v", p, err)
	}
	if p, err := h.lookup(context.Background(), "https://maps.app.goo.gl/elsewhere"); err != nil || p.Name != "" {
		t.Errorf("redirect off Maps: %+v %v", p, err)
	}
	if p, err := h.lookup(context.Background(), "https://maps.app.goo.gl/stop"); err != nil || p.Name != "葵西小学校入口（バス）" || p.Lat == nil || *p.Lat < 34.7697 || *p.Lat > 34.7698 {
		t.Errorf("place page: %+v %v", p, err)
	}
	if _, err := h.lookup(context.Background(), "https://evil.example/x"); err != errNotMaps {
		t.Errorf("non-Maps link: %v", err)
	}
	if strings.Join(hits, ",") != "/short,/elsewhere,/stop" {
		t.Errorf("requests made: %v", hits)
	}
}

type rewrite string

func (to rewrite) RoundTrip(r *http.Request) (*http.Response, error) {
	u, _ := url.Parse(string(to))
	r2 := r.Clone(r.Context())
	r2.URL.Scheme, r2.URL.Host = u.Scheme, u.Host
	return http.DefaultTransport.RoundTrip(r2)
}

func TestPlaceCoords(t *testing.T) {
	for raw, want := range map[string][2]float64{
		"https://www.google.com/maps/place/X/@34.7108,137.7261,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d34.7111!4d137.7265": {34.7111, 137.7265},
		"https://www.google.com/maps/@34.70,137.73,15z":                                                                      {34.70, 137.73},
		"https://maps.google.com/?q=34.7101,137.7342":                                                                        {34.7101, 137.7342},
	} {
		u, _ := url.Parse(raw)
		lat, lng := placeCoords(u)
		if lat == nil || *lat != want[0] || *lng != want[1] {
			t.Errorf("placeCoords(%s) = %v %v", raw, lat, lng)
		}
	}
	u, _ := url.Parse("https://www.google.com/maps/place/X")
	if lat, _ := placeCoords(u); lat != nil {
		t.Errorf("no coordinates, got %v", *lat)
	}
}
