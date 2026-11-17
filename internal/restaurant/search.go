package restaurant

// Finding a place by name as it's typed, so adding one doesn't need a Google
// Maps link: GET /api/restaurants/search?q=いちらん returns restaurants,
// cafés, bars and the like whose names match, from Photon
// (photon.komoot.io), a search-as-you-type geocoder over OpenStreetMap data
// (Nominatim forbids autocomplete). Results near lat/lng, when given, come
// first: the web page sends the person's bus stop, if they have one, so the
// branch in their own town wins over one across the country.
//
// Romaji works when OpenStreetMap has a Latin name for the place (name:en
// and the like), which chains mostly do: "sukiya" finds すき家, "coco ichi"
// CoCo壱番屋. A local shop with only a Japanese name isn't found by romaji;
// Photon has no readings, and spelling romaji out in kana doesn't help, since
// most names are kanji ("matsuya" finds 松屋, "まつや" other shops).
//
// What goes to Photon: the text typed and that position; nothing else. The
// form credits OpenStreetMap, as its licence asks.

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/rudnam/samfran/internal/geo"
	"github.com/rudnam/samfran/internal/httpx"
)

const (
	photon = "https://photon.komoot.io/api/"
	// sameBranch is how close two results with one name must be to be one place.
	sameBranch    = 50.0 // meters
	maxFound      = 8
	searchEntries = 500
	searchFor     = 24 * time.Hour
)

// foodTags are the OpenStreetMap tags of places to eat and drink.
var foodTags = []string{
	"amenity:restaurant", "amenity:fast_food", "amenity:cafe", "amenity:bar", "amenity:pub",
	"amenity:food_court", "amenity:ice_cream", "amenity:biergarten",
	"shop:bakery", "shop:confectionery", "shop:pastry",
}

// FoundPlace is a place to eat that matches what was typed.
type FoundPlace struct {
	Name string `json:"name"`
	// Area is where it is: city and district, as the form's Area field wants.
	Area string `json:"area"`
	// Street is the street or neighbourhood, to tell branches apart.
	Street string `json:"street"`
	// Kind is OpenStreetMap's: restaurant, fast_food, cafe, bar, pub, bakery…
	Kind string  `json:"kind"`
	Lat  float64 `json:"lat"`
	Lng  float64 `json:"lng"`
	// PlaceID is Google's, for a place Google suggested (google.go). Such a
	// suggestion has only its name and Street (an address line) until its
	// details are asked for: GET /api/restaurants/place/{place_id}.
	PlaceID string `json:"place_id,omitempty"`
	// Food is what the form's Food field says, from Google's details: the
	// kind of restaurant ("Ramen Restaurant", "ラーメン屋"), when it says one.
	Food string `json:"food,omitempty"`
	// MapURL is the place's own Google Maps link, from Google's details.
	MapURL string `json:"map_url,omitempty"`
}

// placeSearch asks Photon, remembering recent answers.
type placeSearch struct {
	base   string
	client *http.Client
	mu     sync.Mutex
	cache  map[string]searched
}

type searched struct {
	found []FoundPlace
	at    time.Time
}

func (s *placeSearch) search(ctx context.Context, q string, near *[2]float64) ([]FoundPlace, error) {
	key := q
	if near != nil {
		// Round the bias to about 10 km, so nearby searches share answers.
		key += "@" + strconv.FormatFloat(near[0], 'f', 1, 64) + "," + strconv.FormatFloat(near[1], 'f', 1, 64)
	}
	s.mu.Lock()
	if c, ok := s.cache[key]; ok && time.Since(c.at) < searchFor {
		s.mu.Unlock()
		return c.found, nil
	}
	s.mu.Unlock()

	v := url.Values{"q": {q}, "limit": {"15"}, "osm_tag": foodTags}
	if near != nil {
		v.Set("lat", strconv.FormatFloat(near[0], 'f', 4, 64))
		v.Set("lon", strconv.FormatFloat(near[1], 'f', 4, 64))
		// Rank by distance and match, not how well known a place is: without
		// this, "ラーメン" near Hamamatsu put places 80 km away first, ahead of
		// eight ramen shops in town (tried on Photon, November 2026).
		v.Set("location_bias_scale", "0")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.base+"?"+v.Encode(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "samfran/1.0 (https://samfran.rudnam.com)")
	res, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, httpx.Errorf(http.StatusBadGateway, "the place search isn’t answering; try again in a moment")
	}
	var body struct {
		Features []struct {
			Geometry struct {
				Coordinates []float64 `json:"coordinates"`
			} `json:"geometry"`
			Properties struct {
				Name     string `json:"name"`
				OSMValue string `json:"osm_value"`
				City     string `json:"city"`
				District string `json:"district"`
				Locality string `json:"locality"`
				Street   string `json:"street"`
				State    string `json:"state"`
			} `json:"properties"`
		} `json:"features"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(nil, res.Body, 1<<20)).Decode(&body); err != nil {
		return nil, err
	}
	found := []FoundPlace{}
next:
	for _, f := range body.Features {
		p, c := f.Properties, f.Geometry.Coordinates
		if p.Name == "" || len(c) != 2 {
			continue
		}
		for _, o := range found {
			if o.Name == p.Name && geo.Meters(o.Lat, o.Lng, c[1], c[0]) < sameBranch {
				continue next // one place mapped twice (a point and a building)
			}
		}
		var area []string
		for _, a := range []string{p.City, p.District} {
			if a != "" && (len(area) == 0 || area[0] != a) { // a Tokyo ward is both
				area = append(area, a)
			}
		}
		if len(area) == 0 && p.State != "" {
			area = append(area, p.State)
		}
		street := p.Street
		if street == "" {
			street = p.Locality
		}
		found = append(found, FoundPlace{Name: p.Name, Area: strings.Join(area, " "), Street: street, Kind: p.OSMValue, Lat: c[1], Lng: c[0]})
		if len(found) == maxFound {
			break
		}
	}

	s.mu.Lock()
	if s.cache == nil || len(s.cache) >= searchEntries {
		s.cache = map[string]searched{} // simplest eviction: start again
	}
	s.cache[key] = searched{found, time.Now()}
	s.mu.Unlock()
	return found, nil
}

func (h *Handler) searchHandler(w http.ResponseWriter, r *http.Request) error {
	q, near, ok := searchParams(r)
	if !ok {
		httpx.WriteJSON(w, http.StatusOK, []FoundPlace{})
		return nil
	}
	return h.answerSearch(w, r, q, near)
}

// searchParams reads q (2 to 100 characters, else not ok) and lat/lng, if valid.
func searchParams(r *http.Request) (string, *[2]float64, bool) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if n := utf8.RuneCountInString(q); n < 2 || n > 100 {
		return "", nil, false
	}
	var near *[2]float64
	lat, err1 := strconv.ParseFloat(r.URL.Query().Get("lat"), 64)
	lng, err2 := strconv.ParseFloat(r.URL.Query().Get("lng"), 64)
	if err1 == nil && err2 == nil && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 {
		near = &[2]float64{lat, lng}
	}
	return q, near, true
}

// answerSearch answers from Photon.
func (h *Handler) answerSearch(w http.ResponseWriter, r *http.Request, q string, near *[2]float64) error {
	found, err := h.search.search(r.Context(), q, near)
	if err != nil {
		if _, ok := err.(*httpx.Error); ok {
			return err
		}
		return httpx.Errorf(http.StatusBadGateway, "the place search isn’t answering; try again in a moment")
	}
	httpx.WriteJSON(w, http.StatusOK, found)
	return nil
}
