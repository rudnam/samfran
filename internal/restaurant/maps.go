package restaurant

// Adding a place from Google Maps: the app gets a Maps link (shared to the
// Android app, or pasted), usually with the place's name. When only the link
// came, POST /api/restaurants/lookup reads the name from where the link
// leads. It follows only Google Maps addresses, at most a few redirects, and
// never reads a response body, so it can't be used to fetch other sites.

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/rudnam/samfran/internal/httpx"
)

// Place is what a Maps link tells us.
type Place struct {
	Name   string `json:"name"`
	MapURL string `json:"map_url"`
	// Lat and Lng, when the address gives a position (a bus stop's, say).
	Lat *float64 `json:"lat,omitempty"`
	Lng *float64 `json:"lng,omitempty"`
	// Found says the position was looked up by name on OpenStreetMap,
	// because the link had none.
	Found bool `json:"found_by_name,omitempty"`
}

var errNotMaps = errors.New("not a Google Maps link")

// googleDomain is Google's own domain in any country: google.com,
// google.co.jp, google.de, www.google.com.au…, and nothing that merely
// starts with it (google.com.evil.example).
var googleDomain = regexp.MustCompile(`^(www\.)?google\.(com|[a-z]{2}|com?\.[a-z]{2})$`)

// mapsHost reports whether u is a Google Maps address: a short link
// (maps.app.goo.gl, goo.gl/maps) or a google.<tld>/maps page.
func mapsHost(u *url.URL) bool {
	if u.Scheme != "https" && u.Scheme != "http" {
		return false
	}
	host := strings.ToLower(u.Hostname())
	switch {
	case host == "maps.app.goo.gl":
		return true
	case host == "goo.gl":
		return strings.HasPrefix(u.Path, "/maps")
	case strings.HasPrefix(host, "maps.") && googleDomain.MatchString(strings.TrimPrefix(host, "maps.")):
		return true
	}
	// www.google.com, google.co.jp, …: only their /maps pages.
	return googleDomain.MatchString(host) && (u.Path == "/maps" || strings.HasPrefix(u.Path, "/maps/"))
}

// placeName reads a place's name from a Maps page address:
// /maps/place/<name>/@…, or ?q=<name>, or ?query=<name>.
func placeName(u *url.URL) string {
	if rest, ok := strings.CutPrefix(u.Path, "/maps/place/"); ok {
		seg, _, _ := strings.Cut(rest, "/")
		if name, err := url.PathUnescape(strings.ReplaceAll(seg, "+", " ")); err == nil && name != "" && !strings.HasPrefix(name, "@") {
			return strings.TrimSpace(name)
		}
	}
	for _, key := range []string{"q", "query"} {
		if q := strings.TrimSpace(u.Query().Get(key)); q != "" && !looksLikeCoordinates(q) {
			// "Name, address…": the name is the part before the first comma.
			name, _, _ := strings.Cut(q, ",")
			return strings.TrimSpace(name)
		}
	}
	return ""
}

func looksLikeCoordinates(s string) bool {
	return strings.Trim(s, "0123456789.,-+ ") == ""
}

// lookup follows a Maps link's redirects, staying on Maps addresses, and
// returns the place's name and position as the final address gives them
// (empty when it doesn't).
func (h *Handler) lookup(ctx context.Context, raw string) (Place, error) {
	place := Place{MapURL: strings.TrimSpace(raw)}
	u, err := url.Parse(place.MapURL)
	if err != nil || !mapsHost(u) {
		return place, errNotMaps
	}
	ctx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()
	for hop := 0; ; hop++ {
		if place.Name == "" {
			place.Name = placeName(u)
		}
		if place.Lat == nil {
			place.Lat, place.Lng = placeCoords(u)
		}
		if place.Name != "" && place.Lat != nil || hop == 5 {
			return place, nil
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
		if err != nil {
			return place, err
		}
		req.Header.Set("User-Agent", "Mozilla/5.0 (samfran)")
		res, err := h.maps.Do(req)
		if err != nil {
			return place, err
		}
		res.Body.Close() // only the redirect matters
		loc := res.Header.Get("Location")
		if res.StatusCode < 300 || res.StatusCode > 399 || loc == "" {
			return place, nil
		}
		next, err := u.Parse(loc)
		if err != nil || !mapsHost(next) {
			return place, nil // led somewhere else (e.g. a consent page)
		}
		u = next
	}
}

var (
	// !3d<lat>!4d<lng>: the place itself, in a /maps/place/ address's data.
	pinCoords = regexp.MustCompile(`!3d(-?[0-9]+\.[0-9]+)!4d(-?[0-9]+\.[0-9]+)`)
	// @<lat>,<lng>,<zoom>: where the map is centred.
	viewCoords = regexp.MustCompile(`@(-?[0-9]+\.[0-9]+),(-?[0-9]+\.[0-9]+)`)
	// ?q=<lat>,<lng>
	queryCoords = regexp.MustCompile(`^(-?[0-9]+\.[0-9]+), *(-?[0-9]+\.[0-9]+)$`)
)

// placeCoords reads a position from a Maps address: the pin if there is
// one, else coordinates searched for, else the centre of the map.
func placeCoords(u *url.URL) (lat, lng *float64) {
	for _, try := range []func() []string{
		func() []string { return pinCoords.FindStringSubmatch(u.Path) },
		func() []string { return queryCoords.FindStringSubmatch(strings.TrimSpace(u.Query().Get("q"))) },
		func() []string { return queryCoords.FindStringSubmatch(strings.TrimSpace(u.Query().Get("query"))) },
		func() []string { return viewCoords.FindStringSubmatch(u.Path) },
	} {
		m := try()
		if m == nil {
			continue
		}
		a, err1 := strconv.ParseFloat(m[1], 64)
		b, err2 := strconv.ParseFloat(m[2], 64)
		if err1 == nil && err2 == nil && a >= -90 && a <= 90 && b >= -180 && b <= 180 {
			return &a, &b
		}
	}
	return nil, nil
}

func (h *Handler) lookupHandler(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		URL string `json:"url"`
		// Position asks for lat and lng even when the link has none: then
		// they're looked up by the place's name (geocode.go). The Bus tab
		// needs them; the restaurant form doesn't.
		Position bool `json:"position"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	place, err := h.lookup(r.Context(), in.URL)
	if errors.Is(err, errNotMaps) {
		return httpx.Errorf(http.StatusBadRequest, "that isn't a Google Maps link")
	}
	if err != nil {
		// Google unreachable or slow: the person can still type the name.
		place = Place{MapURL: strings.TrimSpace(in.URL)}
	}
	if in.Position && place.Lat == nil && place.Name != "" && h.places != nil {
		place.Lat, place.Lng = h.places.find(r.Context(), place.Name)
		place.Found = place.Lat != nil
	}
	httpx.WriteJSON(w, http.StatusOK, place)
	return nil
}

// noRedirects makes a client hand back each redirect instead of following
// it, so lookup can check every address before going there.
func noRedirects(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
