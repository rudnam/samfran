package restaurant

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLocate(t *testing.T) {
	var googleAsked, nominatimAsked []string
	google := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/places:searchText" || r.Header.Get("X-Goog-FieldMask") != "places.location" {
			http.NotFound(w, r)
			return
		}
		var body struct{ TextQuery string }
		decodeBody(r, &body)
		googleAsked = append(googleAsked, body.TextQuery)
		switch body.TextQuery {
		case "浜松城公園 浜松市":
			w.Write([]byte(`{"places":[{"location":{"latitude":34.711,"longitude":137.725}}]}`))
		case "broken":
			http.Error(w, "{}", http.StatusInternalServerError)
		default:
			w.Write([]byte(`{}`))
		}
	}))
	defer google.Close()
	nominatim := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		nominatimAsked = append(nominatimAsked, r.URL.Query().Get("q"))
		if r.URL.Query().Get("q") == "中田島砂丘" {
			w.Write([]byte(`[{"lat":"34.656","lon":"137.731","name":"中田島砂丘","type":"dune"}]`))
			return
		}
		w.Write([]byte(`[]`))
	}))
	defer nominatim.Close()

	h := NewHandler(nil)
	h.places = &geocoder{base: nominatim.URL, client: nominatim.Client()}
	ctx := context.Background()

	// The map link says where: nobody is asked.
	lat, lng, sure := h.locate(ctx, Restaurant{Name: "Hanabi", MapURL: "https://www.google.com/maps/place/Hanabi/@34.70,137.73,17z/data=!3d34.7036!4d137.7352"})
	if !sure || lat == nil || *lat != 34.7036 || *lng != 137.7352 || len(nominatimAsked) != 0 {
		t.Errorf("from the link: %v %v %v, asked %v", lat, lng, sure, nominatimAsked)
	}

	// Without a Google key: OpenStreetMap, by the exact name.
	lat, _, sure = h.locate(ctx, Restaurant{Name: "中田島砂丘", Area: "浜松市"})
	if !sure || lat == nil || *lat != 34.656 {
		t.Errorf("from OpenStreetMap: %v %v", lat, sure)
	}
	// Not found anywhere: sure, so it isn't asked about again.
	if lat, _, sure = h.locate(ctx, Restaurant{Name: "どこにもない"}); !sure || lat != nil {
		t.Errorf("nowhere: %v %v", lat, sure)
	}

	// With a key: Google's text search for the name and area, before OpenStreetMap.
	h.UseGoogle("k", google.URL+"/")
	nominatimAsked = nil
	if lat, lng, sure = h.locate(ctx, Restaurant{Name: "浜松城公園", Area: "浜松市"}); !sure || lat == nil || *lat != 34.711 || *lng != 137.725 || len(nominatimAsked) != 0 {
		t.Errorf("from Google: %v %v %v, then asked %v", lat, lng, sure, nominatimAsked)
	}
	// Google failing: not sure, so it's tried again later.
	if lat, _, sure = h.locate(ctx, Restaurant{Name: "broken"}); sure || lat != nil {
		t.Errorf("Google failing: %v %v", lat, sure)
	}
	if len(googleAsked) != 2 {
		t.Errorf("Google asked %v", googleAsked)
	}
}

func TestLocateSoonIsOffUnlessOnAndWaitsBetweenRounds(t *testing.T) {
	h := NewHandler(nil)
	h.locateSoon(1, true) // off: nothing happens (with no store, a round would panic)
	h.Locate = true
	h.locating.last = map[int64]time.Time{1: time.Now()}
	h.locateSoon(1, false) // a round just ran for this space: not again yet
}

func decodeBody(r *http.Request, v any) { _ = json.NewDecoder(r.Body).Decode(v) }
