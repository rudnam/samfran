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
	"strings"
	"time"

	"github.com/rudnam/samfran/internal/httpx"
)

// Place is what a Maps link tells us.
type Place struct {
	Name   string `json:"name"`
	MapURL string `json:"map_url"`
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
// returns the name the final address names ("" if none does).
func (h *Handler) lookup(ctx context.Context, raw string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || !mapsHost(u) {
		return "", errNotMaps
	}
	ctx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()
	for hop := 0; hop < 5; hop++ {
		if name := placeName(u); name != "" {
			return name, nil
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
		if err != nil {
			return "", err
		}
		req.Header.Set("User-Agent", "Mozilla/5.0 (samfran)")
		res, err := h.maps.Do(req)
		if err != nil {
			return "", err
		}
		res.Body.Close() // only the redirect matters
		loc := res.Header.Get("Location")
		if res.StatusCode < 300 || res.StatusCode > 399 || loc == "" {
			return "", nil
		}
		next, err := u.Parse(loc)
		if err != nil || !mapsHost(next) {
			return "", nil // led somewhere else (e.g. a consent page): no name
		}
		u = next
	}
	return "", nil
}

func (h *Handler) lookupHandler(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		URL string `json:"url"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	name, err := h.lookup(r.Context(), in.URL)
	if errors.Is(err, errNotMaps) {
		return httpx.Errorf(http.StatusBadRequest, "that isn't a Google Maps link")
	}
	if err != nil {
		// Google unreachable or slow: the person can still type the name.
		name = ""
	}
	httpx.WriteJSON(w, http.StatusOK, Place{Name: name, MapURL: strings.TrimSpace(in.URL)})
	return nil
}

// noRedirects makes a client hand back each redirect instead of following
// it, so lookup can check every address before going there.
func noRedirects(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
