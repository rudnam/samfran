package bus

// Searching for a stop by name, as it's typed: GET /api/bus/search?q=葵西
// returns bus stops and stations whose names start with or contain it, from
// Photon (photon.komoot.io), a search-as-you-type geocoder over OpenStreetMap
// data. (OpenStreetMap's own Nominatim forbids autocomplete.) Results near
// lat/lng, when given, come first. A stop usually has two entries, one each
// side of the road; they come back as one. A name and its town ("新林 知立")
// finds that town's stops of the name first, however common the name.
//
// What goes to Photon: the text typed and the position of the stop already
// chosen, if any; never the phone's position. The Bus tab credits
// OpenStreetMap, as its licence asks.

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"slices"
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

	found, err := s.stops(ctx, q, near, maxResults)
	if err != nil {
		return nil, err
	}
	// "新林 知立" or "shinbayashi chiryu": a common name, and the town it's in.
	// Photon matches the words against names, so it can miss that; find the
	// town (the last word, or else the first) and the stops named the rest
	// near it, and put those first.
	if words := strings.Fields(q); len(words) >= 2 {
		n := len(words)
		for _, split := range [][2]string{
			{strings.Join(words[:n-1], " "), words[n-1]},
			{strings.Join(words[1:], " "), words[0]},
		} {
			if local := s.inTown(ctx, split[0], split[1]); len(local) > 0 {
				found = merge(local, found)
				break
			}
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

// townRadius is how far from a town's middle its stops can be.
const townRadius = 15000.0 // meters

// inTown is the stops named name in or near the town (city, ward,
// district) named town, in Photon's order (the closest matches to the name
// first). None if there's no such town, or
// Photon doesn't answer: the search as a whole still has its answer.
func (s *Searcher) inTown(ctx context.Context, name, town string) []Found {
	if utf8.RuneCountInString(name) < 2 {
		return nil
	}
	places, err := s.photon(ctx, url.Values{"q": {town}, "limit": {"1"}, "layer": {"city", "district", "locality", "county"}})
	if err != nil || len(places) == 0 || len(places[0].Geometry.Coordinates) != 2 {
		return nil
	}
	c := places[0].Geometry.Coordinates
	at := [2]float64{c[1], c[0]}
	found, err := s.stops(ctx, name, &at, photonLimit)
	if err != nil {
		return nil
	}
	local := []Found{}
	for _, f := range found {
		// A stop in another town by that name.
		if geo.Meters(at[0], at[1], f.Lat, f.Lng) <= townRadius {
			local = append(local, f)
		}
	}
	return local
}

// merge is first, then the stops in rest that aren't in it, up to maxResults.
func merge(first, rest []Found) []Found {
	out := slices.Clone(first)
	for _, f := range rest {
		if !slices.ContainsFunc(out, func(o Found) bool { return o.same(f) }) {
			out = append(out, f)
		}
	}
	return out[:min(len(out), maxResults)]
}

// same is whether two results are one stop: one name, close together (the
// two sides of a road).
func (f Found) same(o Found) bool {
	return f.Name == o.Name && f.Kind == o.Kind && geo.Meters(f.Lat, f.Lng, o.Lat, o.Lng) < sameStop
}

// feature is one of Photon's answers.
type feature struct {
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
}

// photon asks Photon.
func (s *Searcher) photon(ctx context.Context, v url.Values) ([]feature, error) {
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
		Features []feature `json:"features"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(nil, res.Body, 1<<20)).Decode(&body); err != nil {
		return nil, err
	}
	return body.Features, nil
}

// photonLimit is how many answers to ask Photon for: a stop is often two.
const photonLimit = 20

// stops are up to max bus stops and stations named q, near ones first when near is given.
func (s *Searcher) stops(ctx context.Context, q string, near *[2]float64, max int) ([]Found, error) {
	v := url.Values{"q": {q}, "limit": {strconv.Itoa(photonLimit)}, "osm_tag": {"highway:bus_stop", "railway:station", "railway:halt"}}
	if near != nil {
		v.Set("lat", strconv.FormatFloat(near[0], 'f', 4, 64))
		v.Set("lon", strconv.FormatFloat(near[1], 'f', 4, 64))
	}
	features, err := s.photon(ctx, v)
	if err != nil {
		return nil, err
	}
	found := []Found{}
next:
	for _, f := range features {
		p, c := f.Properties, f.Geometry.Coordinates
		if p.Name == "" || len(c) != 2 {
			continue
		}
		kind := "bus"
		if p.OSMKey == "railway" {
			kind = "train"
		}
		this := Found{Name: p.Name, Kind: kind, Lat: c[1], Lng: c[0]}
		for _, o := range found {
			if o.same(this) {
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
		this.Area = strings.Join(area, " ")
		found = append(found, this)
		if len(found) == max {
			break
		}
	}
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
