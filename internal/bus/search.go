package bus

// Searching for a stop by name, as it's typed: GET /api/bus/search?q=葵西
// returns bus stops and stations whose names start with or contain it, from
// Photon (photon.komoot.io), a search-as-you-type geocoder over OpenStreetMap
// data. (OpenStreetMap's own Nominatim forbids autocomplete.) Results near
// lat/lng, when given, come first. A stop usually has two entries, one each
// side of the road; they come back as one.
//
// What goes to Photon: the text typed and the position of the stop already
// chosen, if any; never the phone's position. The Bus tab credits
// OpenStreetMap, as its licence asks.

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
	// sameStop is how close two results with one name must be to be one stop.
	sameStop     = 300.0 // meters
	maxResults   = 8
	cacheEntries = 500
	cacheFor     = 24 * time.Hour
)

// Found is a stop that matches.
type Found struct {
	Name string `json:"name"`
	// Area is where it is: city, district, street.
	Area string `json:"area"`
	// Kind is "bus" or "train".
	Kind string  `json:"kind"`
	Lat  float64 `json:"lat"`
	Lng  float64 `json:"lng"`
}

// Searcher asks Photon, remembering recent answers.
type Searcher struct {
	base   string
	client *http.Client
	mu     sync.Mutex
	cache  map[string]cached
}

type cached struct {
	found []Found
	at    time.Time
}

func NewSearcher() *Searcher {
	return &Searcher{base: photon, client: &http.Client{Timeout: 5 * time.Second}}
}

func (s *Searcher) Search(ctx context.Context, q string, near *[2]float64) ([]Found, error) {
	key := q
	if near != nil {
		// Round the bias to about 10 km, so nearby searches share answers.
		key += "@" + strconv.FormatFloat(near[0], 'f', 1, 64) + "," + strconv.FormatFloat(near[1], 'f', 1, 64)
	}
	s.mu.Lock()
	if c, ok := s.cache[key]; ok && time.Since(c.at) < cacheFor {
		s.mu.Unlock()
		return c.found, nil
	}
	s.mu.Unlock()

	v := url.Values{"q": {q}, "limit": {"20"}, "osm_tag": {"highway:bus_stop", "railway:station", "railway:halt"}}
	if near != nil {
		v.Set("lat", strconv.FormatFloat(near[0], 'f', 4, 64))
		v.Set("lon", strconv.FormatFloat(near[1], 'f', 4, 64))
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
		return nil, httpx.Errorf(http.StatusBadGateway, "the stop search isn’t answering; try again in a moment")
	}
	var body struct {
		Features []struct {
			Geometry struct {
				Coordinates []float64 `json:"coordinates"`
			} `json:"geometry"`
			Properties struct {
				Name     string `json:"name"`
				OSMKey   string `json:"osm_key"`
				City     string `json:"city"`
				District string `json:"district"`
				Street   string `json:"street"`
				State    string `json:"state"`
			} `json:"properties"`
		} `json:"features"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(nil, res.Body, 1<<20)).Decode(&body); err != nil {
		return nil, err
	}
	found := []Found{}
next:
	for _, f := range body.Features {
		p, c := f.Properties, f.Geometry.Coordinates
		if p.Name == "" || len(c) != 2 {
			continue
		}
		kind := "bus"
		if p.OSMKey == "railway" {
			kind = "train"
		}
		for _, o := range found {
			if o.Name == p.Name && o.Kind == kind && geo.Meters(o.Lat, o.Lng, c[1], c[0]) < sameStop {
				continue next // the other side of the road
			}
		}
		var area []string
		for _, a := range []string{p.City, p.District, p.Street} {
			if a != "" {
				area = append(area, a)
			}
		}
		if len(area) == 0 && p.State != "" {
			area = append(area, p.State)
		}
		found = append(found, Found{Name: p.Name, Area: strings.Join(area, " "), Kind: kind, Lat: c[1], Lng: c[0]})
		if len(found) == maxResults {
			break
		}
	}

	s.mu.Lock()
	if s.cache == nil || len(s.cache) >= cacheEntries {
		s.cache = map[string]cached{} // simplest eviction: start again
	}
	s.cache[key] = cached{found, time.Now()}
	s.mu.Unlock()
	return found, nil
}

func (h *Handler) search(w http.ResponseWriter, r *http.Request) error {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if n := utf8.RuneCountInString(q); n < 2 || n > 80 {
		httpx.WriteJSON(w, http.StatusOK, []Found{})
		return nil
	}
	var near *[2]float64
	lat, err1 := strconv.ParseFloat(r.URL.Query().Get("lat"), 64)
	lng, err2 := strconv.ParseFloat(r.URL.Query().Get("lng"), 64)
	if err1 == nil && err2 == nil && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 {
		near = &[2]float64{lat, lng}
	}
	found, err := h.Stops.Search(r.Context(), q, near)
	if err != nil {
		if _, ok := err.(*httpx.Error); ok {
			return err
		}
		return httpx.Errorf(http.StatusBadGateway, "the stop search isn’t answering; try again in a moment")
	}
	httpx.WriteJSON(w, http.StatusOK, found)
	return nil
}
