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
			w.Header().Set("Location", "https://www.google.com/maps/place/Hanabi+Grill/@35,137")
		case "/elsewhere":
			w.Header().Set("Location", "https://evil.example/steal")
		}
		w.WriteHeader(http.StatusFound)
	}))
	defer google.Close()
	// Send every request to the fake, whatever its host.
	h := &Handler{maps: &http.Client{CheckRedirect: noRedirects, Transport: rewrite(google.URL)}}

	if name, err := h.lookup(context.Background(), "https://maps.app.goo.gl/short"); err != nil || name != "Hanabi Grill" {
		t.Errorf("short link: %q %v", name, err)
	}
	if name, err := h.lookup(context.Background(), "https://maps.app.goo.gl/elsewhere"); err != nil || name != "" {
		t.Errorf("redirect off Maps: %q %v", name, err)
	}
	if _, err := h.lookup(context.Background(), "https://evil.example/x"); err != errNotMaps {
		t.Errorf("non-Maps link: %v", err)
	}
	if strings.Join(hits, ",") != "/short,/elsewhere" {
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
