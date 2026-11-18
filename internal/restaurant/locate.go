package restaurant

// Where each place is, for sorting by distance and the map. A place picked
// from the search comes with its position. The others (typed by hand,
// shared from Maps, or saved before positions were kept) are looked up in
// the background, a few at a time, after one is added or edited, or when
// the list is opened and some have none:
//
//  1. the map link, when it says where (a pin, coordinates, the place's
//     feature ID), following a short link's redirects as maps.go does;
//  2. Google Places' text search for the name and area, when there's a key;
//  3. OpenStreetMap's geocoder for the exact name, only when every match is
//     in one spot (geocode.go).
//
// A place none of them find is marked as looked up, and asked about again
// only when its map link changes.

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	// locateBatch is how many places one round looks up.
	locateBatch = 20
	// locateEvery is how often opening the list starts a round for a space.
	locateEvery = 10 * time.Minute
)

// locating remembers when each space's last round started, so opening the
// list again and again doesn't start a round each time.
type locating struct {
	mu   sync.Mutex
	last map[int64]time.Time
	// wait, in tests, runs the round before the request answers.
	wait bool
}

// locateSoon looks up the space's places that have no position yet, in the
// background. now skips the wait between rounds: something was just added
// or edited.
func (h *Handler) locateSoon(spaceID int64, now bool) {
	if !h.Locate {
		return
	}
	h.locating.mu.Lock()
	if h.locating.last == nil {
		h.locating.last = map[int64]time.Time{}
	}
	if !now && time.Since(h.locating.last[spaceID]) < locateEvery {
		h.locating.mu.Unlock()
		return
	}
	h.locating.last[spaceID] = time.Now()
	wait := h.locating.wait
	h.locating.mu.Unlock()

	round := func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		if err := h.locateSpace(ctx, spaceID); err != nil {
			slog.WarnContext(ctx, "looking up places' positions failed", "space", spaceID, "err", err)
		}
	}
	if wait {
		round()
		return
	}
	go round()
}

func (h *Handler) locateSpace(ctx context.Context, spaceID int64) error {
	list, err := h.store.Unlocated(ctx, spaceID, locateBatch)
	if err != nil {
		return err
	}
	for _, p := range list {
		lat, lng, sure := h.locate(ctx, p)
		if !sure {
			continue // a search didn't answer: try again next round
		}
		if err := h.store.Located(ctx, spaceID, p.ID, p.MapURL, lat, lng); err != nil {
			return err
		}
	}
	return nil
}

// locate finds where p is. sure is false when a search that could have
// found it didn't answer, so it's worth asking again later.
func (h *Handler) locate(ctx context.Context, p Restaurant) (lat, lng *float64, sure bool) {
	if p.MapURL != "" {
		if found, err := h.lookup(ctx, p.MapURL); err == nil && found.Lat != nil {
			return found.Lat, found.Lng, true
		}
	}
	if h.google != nil {
		lat, lng, err := h.google.find(ctx, strings.TrimSpace(p.Name+" "+p.Area))
		if err != nil {
			slog.WarnContext(ctx, "google places text search failed", "err", err)
			return nil, nil, false
		}
		if lat != nil {
			return lat, lng, true
		}
	}
	if h.places != nil {
		lat, lng := h.places.find(ctx, p.Name)
		return lat, lng, true
	}
	return nil, nil, true
}

// find is Google's best match for q (a place's name and area) in Japan:
// Text Search, asking only for the position.
func (g *googlePlaces) find(ctx context.Context, q string) (lat, lng *float64, err error) {
	body := map[string]any{"textQuery": q, "languageCode": "ja", "regionCode": "jp", "pageSize": 1}
	var out struct {
		Places []struct {
			Location *struct {
				Latitude  float64 `json:"latitude"`
				Longitude float64 `json:"longitude"`
			} `json:"location"`
		} `json:"places"`
	}
	if err := g.do(ctx, http.MethodPost, "places:searchText", body, "places.location", &out); err != nil {
		return nil, nil, err
	}
	if len(out.Places) == 0 || out.Places[0].Location == nil {
		return nil, nil, nil
	}
	a, b := out.Places[0].Location.Latitude, out.Places[0].Location.Longitude
	return &a, &b, nil
}
