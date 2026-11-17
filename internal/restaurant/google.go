package restaurant

// Finding a place by name with Google Places (API "Places API (New)"), when
// GOOGLE_PLACES_API_KEY is set: GET /api/restaurants/suggest?q=… answers as
// it's typed (Autocomplete), and GET /api/restaurants/place/{id} gives the
// picked one's details (Place Details): where it is, its Google Maps link,
// and what kind of food it serves, for the form's Area, map link and Food.
// Google matches romaji and readings ("ishimatsu" finds 石松), which
// OpenStreetMap can't. Without a key, or when Google fails, suggest answers
// from Photon instead (search.go).
//
// The two calls share the form's session token, so Google bills the typing
// and the pick as one session. What goes to Google: the text typed, the
// person's bus stop (to rank nearby places first), and the app's language.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"

	"github.com/rudnam/samfran/internal/httpx"
)

const googlePlacesBase = "https://places.googleapis.com/v1/"

// googleFood are the place types Google suggests: places to eat and drink.
// Autocomplete takes at most five, and matches the place's primary type
// exactly, so "restaurant" doesn't cover "ramen_restaurant"; "food" is the
// group that does.
var googleFood = []string{"food", "restaurant", "cafe", "bar", "bakery"}

// plainTypes say nothing about the food: Food stays empty for them.
var plainTypes = []string{"restaurant", "food", "meal_takeaway", "meal_delivery", "point_of_interest", "establishment", "store", "food_store"}

// validPlaceID and validSession keep what's sent to Google to what it gave.
var (
	validPlaceID = regexp.MustCompile(`^[A-Za-z0-9_-]{10,300}$`)
	validSession = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
)

type googlePlaces struct {
	base   string
	key    string
	client *http.Client
}

// language is the app's language as Google takes it, Japanese or English:
// lang= (the web page), else Accept-Language (the Android app).
func language(r *http.Request) string {
	lang := r.URL.Query().Get("lang")
	if lang == "" {
		lang = r.Header.Get("Accept-Language")
	}
	if strings.HasPrefix(strings.ToLower(strings.TrimSpace(lang)), "ja") {
		return "ja"
	}
	return "en"
}

func (g *googlePlaces) do(ctx context.Context, method, path string, body any, fieldMask string, out any) error {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, g.base+path, rd)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Goog-Api-Key", g.key)
	if fieldMask != "" {
		req.Header.Set("X-Goog-FieldMask", fieldMask)
	}
	res, err := g.client.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 2000))
		return fmt.Errorf("google places %s: %s: %s", path, res.Status, bytes.TrimSpace(msg))
	}
	return json.NewDecoder(http.MaxBytesReader(nil, res.Body, 1<<20)).Decode(out)
}

// suggest is what Autocomplete suggests for q: places to eat with their
// place IDs, a name and an address line, but no position yet.
func (g *googlePlaces) suggest(ctx context.Context, q string, near *[2]float64, lang, session string) ([]FoundPlace, error) {
	body := map[string]any{
		"input":                q,
		"includedPrimaryTypes": googleFood,
		"includedRegionCodes":  []string{"jp"},
		"languageCode":         lang,
		"regionCode":           "jp",
	}
	if near != nil {
		body["locationBias"] = map[string]any{"circle": map[string]any{
			"center": map[string]float64{"latitude": near[0], "longitude": near[1]},
			"radius": 50000.0, // meters, Google's largest
		}}
		body["origin"] = map[string]float64{"latitude": near[0], "longitude": near[1]}
	}
	if session != "" {
		body["sessionToken"] = session
	}
	var out struct {
		Suggestions []struct {
			PlacePrediction *struct {
				PlaceID          string `json:"placeId"`
				StructuredFormat struct {
					MainText      struct{ Text string } `json:"mainText"`
					SecondaryText struct{ Text string } `json:"secondaryText"`
				} `json:"structuredFormat"`
				Types []string `json:"types"`
			} `json:"placePrediction"`
		} `json:"suggestions"`
	}
	if err := g.do(ctx, http.MethodPost, "places:autocomplete", body, "", &out); err != nil {
		return nil, err
	}
	found := []FoundPlace{}
	for _, s := range out.Suggestions {
		p := s.PlacePrediction
		if p == nil || p.PlaceID == "" || p.StructuredFormat.MainText.Text == "" {
			continue
		}
		kind := ""
		if len(p.Types) > 0 {
			kind = p.Types[0]
		}
		found = append(found, FoundPlace{
			Name:    p.StructuredFormat.MainText.Text,
			Street:  shortAddress(p.StructuredFormat.SecondaryText.Text),
			Kind:    kind,
			PlaceID: p.PlaceID,
		})
		if len(found) == maxFound {
			break
		}
	}
	return found, nil
}

// shortAddress drops the country and prefecture-level noise Google puts
// first ("日本、〒430-0926 静岡県浜松市…"), keeping what tells branches apart.
func shortAddress(s string) string {
	for _, p := range []string{"日本、", "Japan, ", "日本 "} {
		s = strings.TrimPrefix(s, p)
	}
	s = strings.TrimSuffix(s, ", Japan")
	s = strings.TrimSuffix(s, "、日本")
	if strings.HasPrefix(s, "〒") {
		if i := strings.IndexAny(s, " 　"); i > 0 {
			s = strings.TrimSpace(s[i:])
		}
	}
	return s
}

// details is the picked place: name, area, street, position, Google Maps
// link and, when its type says something about the food, Food.
func (g *googlePlaces) details(ctx context.Context, id, lang, session string) (FoundPlace, error) {
	v := url.Values{"languageCode": {lang}, "regionCode": {"jp"}}
	if session != "" {
		v.Set("sessionToken", session)
	}
	var p struct {
		ID          string `json:"id"`
		DisplayName struct {
			Text string `json:"text"`
		} `json:"displayName"`
		AddressComponents []struct {
			LongText string   `json:"longText"`
			Types    []string `json:"types"`
		} `json:"addressComponents"`
		Location struct {
			Latitude  float64 `json:"latitude"`
			Longitude float64 `json:"longitude"`
		} `json:"location"`
		PrimaryType            string `json:"primaryType"`
		PrimaryTypeDisplayName struct {
			Text string `json:"text"`
		} `json:"primaryTypeDisplayName"`
		GoogleMapsURI string `json:"googleMapsUri"`
	}
	mask := "id,displayName,addressComponents,location,primaryType,primaryTypeDisplayName,googleMapsUri"
	if err := g.do(ctx, http.MethodGet, "places/"+id+"?"+v.Encode(), nil, mask, &p); err != nil {
		return FoundPlace{}, err
	}
	component := func(kind string) string {
		for _, c := range p.AddressComponents {
			if slices.Contains(c.Types, kind) {
				return c.LongText
			}
		}
		return ""
	}
	var area []string
	for _, a := range []string{component("locality"), component("sublocality_level_1")} {
		if a != "" && !slices.Contains(area, a) {
			area = append(area, a)
		}
	}
	if len(area) == 0 {
		if s := component("administrative_area_level_1"); s != "" {
			area = append(area, s)
		}
	}
	street := component("sublocality_level_2")
	if street == "" {
		street = component("route")
	}
	food := ""
	if p.PrimaryType != "" && !slices.Contains(plainTypes, p.PrimaryType) {
		food = p.PrimaryTypeDisplayName.Text
	}
	return FoundPlace{
		Name:    p.DisplayName.Text,
		Area:    strings.Join(area, " "),
		Street:  street,
		Kind:    p.PrimaryType,
		Lat:     p.Location.Latitude,
		Lng:     p.Location.Longitude,
		PlaceID: p.ID,
		Food:    food,
		MapURL:  p.GoogleMapsURI,
	}, nil
}

// suggestHandler is GET /api/restaurants/suggest: Google's suggestions when
// it has a key and answers, Photon's otherwise.
func (h *Handler) suggestHandler(w http.ResponseWriter, r *http.Request) error {
	q, near, ok := searchParams(r)
	if !ok {
		httpx.WriteJSON(w, http.StatusOK, []FoundPlace{})
		return nil
	}
	if h.google != nil {
		session := r.URL.Query().Get("session")
		if !validSession.MatchString(session) {
			session = ""
		}
		found, err := h.google.suggest(r.Context(), q, near, language(r), session)
		if err == nil {
			httpx.WriteJSON(w, http.StatusOK, found)
			return nil
		}
		slog.WarnContext(r.Context(), "google places suggest failed; using Photon", "err", err)
	}
	return h.answerSearch(w, r, q, near)
}

// placeHandler is GET /api/restaurants/place/{id}: a suggested place's details.
func (h *Handler) placeHandler(w http.ResponseWriter, r *http.Request) error {
	id := r.PathValue("id")
	if h.google == nil || !validPlaceID.MatchString(id) {
		return httpx.Errorf(http.StatusNotFound, "no such place")
	}
	session := r.URL.Query().Get("session")
	if !validSession.MatchString(session) {
		session = ""
	}
	p, err := h.google.details(r.Context(), id, language(r), session)
	if err != nil {
		slog.WarnContext(r.Context(), "google places details failed", "err", err)
		return httpx.Errorf(http.StatusBadGateway, "the place search isn’t answering; try again in a moment")
	}
	httpx.WriteJSON(w, http.StatusOK, p)
	return nil
}
