package restaurant

// Finding a place's position by its name, for a Maps link that has none (a
// Google Maps short link to a named place usually ends at an address with
// only the place's ID). Google's page doesn't say where the place is without
// running its scripts, so this asks OpenStreetMap's geocoder (Nominatim) for
// the name instead, and trusts the answer only when every match is in one
// spot: a bus stop's name usually has two (one each side of the road), but
// a name used in several towns gets no position rather than a wrong one.
//
// Nominatim's usage policy: at most one request a second, an identifying
// User-Agent, and credit (the Bus tab says where the position came from).

import (
	"context"
	"encoding/json"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	nominatim = "https://nominatim.openstreetmap.org/search"
	// sameSpot is how far apart matches can be and still be one place.
	sameSpot = 1000.0 // meters
)

// geocoder asks Nominatim, one request a second at most.
type geocoder struct {
	base   string
	client *http.Client
	// gap is the least time between requests.
	gap  time.Duration
	mu   sync.Mutex
	last time.Time
}

// stopSuffixes are what Google Maps adds to a transit stop's name.
var stopSuffixes = []string{"（バス）", "(バス)", " (Bus)", "（Bus）"}

// find returns where name is, or nils if it's unknown or ambiguous.
func (g *geocoder) find(ctx context.Context, name string) (lat, lng *float64) {
	busStop := false
	for _, s := range stopSuffixes {
		if trimmed, ok := strings.CutSuffix(name, s); ok {
			name, busStop = strings.TrimSpace(trimmed), true
		}
	}
	if name == "" {
		return nil, nil
	}
	g.mu.Lock()
	if wait := g.gap - time.Since(g.last); wait > 0 {
		select {
		case <-time.After(wait):
		case <-ctx.Done():
			g.mu.Unlock()
			return nil, nil
		}
	}
	g.last = time.Now()
	g.mu.Unlock()

	q := url.Values{"q": {name}, "format": {"jsonv2"}, "limit": {"10"}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, g.base+"?"+q.Encode(), nil)
	if err != nil {
		return nil, nil
	}
	req.Header.Set("User-Agent", "samfran/1.0 (https://samfran.rudnam.com)")
	res, err := g.client.Do(req)
	if err != nil {
		return nil, nil
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, nil
	}
	var found []struct {
		Lat      string `json:"lat"`
		Lon      string `json:"lon"`
		Type     string `json:"type"`
		Name     string `json:"name"`
		Category string `json:"category"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(nil, res.Body, 1<<20)).Decode(&found); err != nil {
		return nil, nil
	}
	type point struct{ lat, lng float64 }
	var pts []point
	for _, f := range found {
		// Only exact names (Nominatim also returns near misses), and for a
		// bus stop, only bus stops.
		if f.Name != name || busStop && f.Type != "bus_stop" && f.Type != "platform" && f.Type != "stop_position" {
			continue
		}
		a, err1 := strconv.ParseFloat(f.Lat, 64)
		b, err2 := strconv.ParseFloat(f.Lon, 64)
		if err1 == nil && err2 == nil {
			pts = append(pts, point{a, b})
		}
	}
	if len(pts) == 0 {
		return nil, nil
	}
	var sumLat, sumLng float64
	for _, p := range pts {
		if meters(pts[0].lat, pts[0].lng, p.lat, p.lng) > sameSpot {
			return nil, nil // the name is in more than one place
		}
		sumLat, sumLng = sumLat+p.lat, sumLng+p.lng
	}
	a, b := sumLat/float64(len(pts)), sumLng/float64(len(pts))
	return &a, &b
}

// meters is the distance between two points (haversine).
func meters(lat1, lng1, lat2, lng2 float64) float64 {
	const r = 6371000.0
	rad := math.Pi / 180
	dLat, dLng := (lat2-lat1)*rad, (lng2-lng1)*rad
	h := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dLng/2)*math.Sin(dLng/2)
	return 2 * r * math.Asin(math.Sqrt(h))
}
