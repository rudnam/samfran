package restaurant

// Finding a place by name with Google Places (API "Places API (New)"), when
// GOOGLE_PLACES_API_KEY is set: GET /api/restaurants/suggest?q=… answers as
// it's typed (Autocomplete), and GET /api/restaurants/place/{id} gives the
// picked one's details (Place Details): where it is, its Google Maps link,
// what kind of place it is and its category, for the form's Area, map link,
// Kind and category.
// Google matches romaji and readings ("ishimatsu" finds 石松), which
// OpenStreetMap can't. Without a key, or when Google fails, suggest answers
// from Photon instead (search.go).
//
// Autocomplete doesn't rank by how well known a place is: from Hamamatsu,
// "大仏" suggested a baseball field in Mie and Nara's 大仏殿, never 鎌倉大仏
// (高徳院), with or without the location bias. So alongside it, suggest asks
// Text Search for q anywhere in Japan and adds its well-known places
// (wellKnown) after the nearby ones: "大仏" then also has 高徳院 and 牛久大仏,
// while "ラーメン" and "sukiya" add nothing (tried November 2026).
//
// Autocomplete and Place Details share the form's session token, so Google
// bills the typing and the pick as one session; Text Search is billed per
// call. What goes to Google: the text typed, where the person is (to rank
// nearby places first; not sent to Text Search), and the app's language.

import (
	"bytes"
	"cmp"
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
	"sync"

	"github.com/rudnam/samfran/internal/geo"
	"github.com/rudnam/samfran/internal/httpx"
)

const googlePlacesBase = "https://places.googleapis.com/v1/"

// placeTypes say a suggestion is a place you could go to, not an address,
// a street or a town: Autocomplete is asked for any type (it takes at most
// five, too few for every kind of place), and these are kept.
var placeTypes = []string{"establishment", "point_of_interest", "natural_feature", "tourist_attraction"}

// isPlace reports whether a suggestion with these types is a place to go.
func isPlace(types []string) bool {
	for _, t := range types {
		if slices.Contains(placeTypes, t) {
			return true
		}
	}
	return googleCategory(types) != CategoryOther
}

// plainTypes say nothing about the kind of place: Food stays empty for them.
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

// suggest is what Autocomplete suggests for q: places with their place IDs,
// a name, an address line and a category, but no position yet.
func (g *googlePlaces) suggest(ctx context.Context, q string, near *[2]float64, lang, session string) ([]FoundPlace, error) {
	body := map[string]any{
		"input":               q,
		"includedRegionCodes": []string{"jp"},
		// Names in Japanese, as on the shop's sign, whatever the app's language.
		"languageCode": "ja",
		"regionCode":   "jp",
	}
	if near != nil {
		body["locationBias"] = map[string]any{"circle": map[string]any{
			"center": map[string]float64{"latitude": near[0], "longitude": near[1]},
			// Meters. A bias, not a limit: with nothing near, places farther
			// away still come. Tried near Hamamatsu: at 50 km, "komeda" put a
			// branch 214 km away first; at 3 km, the five nearest came.
			"radius": 3000.0,
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
				// How far it is from origin, when one was sent.
				DistanceMeters *int `json:"distanceMeters"`
			} `json:"placePrediction"`
		} `json:"suggestions"`
	}
	if err := g.do(ctx, http.MethodPost, "places:autocomplete", body, "", &out); err != nil {
		return nil, err
	}
	found := []FoundPlace{}
	for _, s := range out.Suggestions {
		p := s.PlacePrediction
		if p == nil || p.PlaceID == "" || p.StructuredFormat.MainText.Text == "" || !isPlace(p.Types) {
			continue
		}
		found = append(found, FoundPlace{
			Name:     p.StructuredFormat.MainText.Text,
			Street:   shortAddress(p.StructuredFormat.SecondaryText.Text),
			Kind:     suggestedKind(p.Types),
			Category: googleCategory(p.Types),
			PlaceID:  p.PlaceID,
			Meters:   p.DistanceMeters,
			Source:   SourceGoogle,
		})
		if len(found) == maxFound {
			break
		}
	}
	return found, nil
}

// iconKinds are the types an older Android app has an icon for, most
// telling first. The app now shows the category's icon instead.
var iconKinds = []string{"ramen_restaurant", "sushi_restaurant", "hamburger_restaurant", "fast_food_restaurant",
	"coffee_shop", "cafe", "ice_cream_shop", "dessert_shop", "bakery", "izakaya_restaurant", "bar"}

// suggestedKind is the kind for a suggestion's icon. A suggestion's types
// come in no particular order (Sukiya's listed "cafe" first), so the most
// telling one is chosen: one with an icon, else any kind of restaurant,
// else the first that isn't a group like "establishment".
func suggestedKind(types []string) string {
	for _, k := range iconKinds {
		if slices.Contains(types, k) {
			return k
		}
	}
	for _, t := range types {
		if strings.HasSuffix(t, "_restaurant") {
			return t
		}
	}
	for _, t := range types {
		if !slices.Contains(placeTypes, t) && !slices.Contains(plainTypes, t) {
			return t
		}
	}
	return "restaurant"
}

// typeInEnglish spells out a place type: "ramen_restaurant" → "Ramen restaurant".
func typeInEnglish(t string) string {
	s := strings.ReplaceAll(t, "_", " ")
	if s == "" {
		return ""
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

// mapLink is the place's Google Maps link without the tracking Google adds
// (…/?cid=123&g_mp=…): just its cid, which is all Maps needs.
func mapLink(uri string) string {
	u, err := url.Parse(uri)
	if err != nil {
		return ""
	}
	if cid := u.Query().Get("cid"); cid != "" {
		return "https://maps.google.com/?" + url.Values{"cid": {cid}}.Encode()
	}
	if u.Scheme != "https" {
		return ""
	}
	return uri
}

// shortAddress drops the country and postcode Google may add ("日本、〒430-0926
// 静岡県浜松市…", "６−１ 砂山町 中央区 浜松市 静岡県 日本"), keeping what tells
// branches apart.
func shortAddress(s string) string {
	for _, p := range []string{"日本、", "Japan, ", "日本 "} {
		s = strings.TrimPrefix(s, p)
	}
	s = strings.TrimSuffix(s, ", Japan")
	s = strings.TrimSuffix(s, "、日本")
	s = strings.TrimSuffix(s, " 日本")
	if strings.HasPrefix(s, "〒") {
		if i := strings.IndexAny(s, " 　"); i > 0 {
			s = strings.TrimSpace(s[i:])
		}
	}
	return s
}

// details is the picked place: name, area, street, position, Google Maps
// link, category and, when its type says what kind of place it is, Food. The name and
// address are always in Japanese, as on the shop's sign and in Maps; Food is
// in lang: Google's own words in Japanese, or the type spelled out in English
// ("ramen_restaurant" → "Ramen restaurant", as Google words it), so one
// request serves both.
func (g *googlePlaces) details(ctx context.Context, id, lang, session string) (FoundPlace, error) {
	v := url.Values{"languageCode": {"ja"}, "regionCode": {"jp"}}
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
		Types                  []string `json:"types"`
		PrimaryType            string   `json:"primaryType"`
		PrimaryTypeDisplayName struct {
			Text string `json:"text"`
		} `json:"primaryTypeDisplayName"`
		GoogleMapsURI string `json:"googleMapsUri"`
	}
	mask := "id,displayName,addressComponents,location,types,primaryType,primaryTypeDisplayName,googleMapsUri"
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
		if lang != "ja" {
			food = typeInEnglish(p.PrimaryType)
		}
	}
	return FoundPlace{
		Category: primaryCategory(p.PrimaryType, p.Types),
		Name:     p.DisplayName.Text,
		Area:     strings.Join(area, " "),
		Street:   street,
		Kind:     p.PrimaryType,
		Lat:      p.Location.Latitude,
		Lng:      p.Location.Longitude,
		PlaceID:  p.ID,
		Food:     food,
		MapURL:   mapLink(p.GoogleMapsURI),
		Source:   SourceGoogle,
	}, nil
}

// primaryCategory is a place's category, its primary type first: a café
// whose types also say "store" is a café.
func primaryCategory(primary string, types []string) string {
	if primary != "" {
		if c := googleCategory([]string{primary}); c != CategoryOther {
			return c
		}
	}
	return googleCategory(types)
}

const (
	// wellKnownReviews is how many Google reviews make a place well known:
	// 高徳院 has 33,716, 牛久大仏 14,146, 東大寺大仏殿 3,617; the most-reviewed
	// ラーメン shop or すき家 anywhere under 1,700 (November 2026).
	wellKnownReviews = 3000
	maxWellKnown     = 3
)

// japan is the box Text Search looks in. Text Search with no location at
// all found nothing for "大仏".
var japan = map[string]any{"rectangle": map[string]any{
	"low":  map[string]float64{"latitude": 24, "longitude": 122},
	"high": map[string]float64{"latitude": 46, "longitude": 146},
}}

// textPlace is a place as Text Search gives it.
type textPlace struct {
	ID          string `json:"id"`
	DisplayName struct {
		Text string `json:"text"`
	} `json:"displayName"`
	ShortFormattedAddress string   `json:"shortFormattedAddress"`
	Types                 []string `json:"types"`
	PrimaryType           string   `json:"primaryType"`
	Location              struct {
		Latitude  float64 `json:"latitude"`
		Longitude float64 `json:"longitude"`
	} `json:"location"`
	UserRatingCount int `json:"userRatingCount"`
}

// wellKnown is what Text Search finds for q anywhere in Japan, without
// regard to where the person is, keeping the well-known places: those with
// wellKnownReviews or more, the most reviewed first. Like a suggestion, each
// has a name and an address line until its details are asked for, and how
// far it is from near, when given.
func (g *googlePlaces) wellKnown(ctx context.Context, q string, near *[2]float64) ([]FoundPlace, error) {
	body := map[string]any{
		"textQuery":           q,
		"languageCode":        "ja",
		"regionCode":          "jp",
		"pageSize":            20,
		"locationRestriction": japan,
	}
	var out struct {
		Places []textPlace `json:"places"`
	}
	mask := "places.id,places.displayName,places.shortFormattedAddress,places.types,places.primaryType,places.location,places.userRatingCount"
	if err := g.do(ctx, http.MethodPost, "places:searchText", body, mask, &out); err != nil {
		return nil, err
	}
	places := slices.DeleteFunc(out.Places, func(p textPlace) bool {
		return p.UserRatingCount < wellKnownReviews || p.DisplayName.Text == "" || !validPlaceID.MatchString(p.ID) || !isPlace(p.Types)
	})
	slices.SortStableFunc(places, func(a, b textPlace) int { return cmp.Compare(b.UserRatingCount, a.UserRatingCount) })
	found := []FoundPlace{}
	for _, p := range places[:min(len(places), maxWellKnown)] {
		f := FoundPlace{
			Name:     p.DisplayName.Text,
			Street:   shortAddress(p.ShortFormattedAddress),
			Kind:     cmp.Or(p.PrimaryType, suggestedKind(p.Types)),
			Category: primaryCategory(p.PrimaryType, p.Types),
			PlaceID:  p.ID,
			Source:   SourceGoogle,
		}
		if near != nil {
			m := int(geo.Meters(near[0], near[1], p.Location.Latitude, p.Location.Longitude) + 0.5)
			f.Meters = &m
		}
		found = append(found, f)
	}
	return found, nil
}

// suggestions are Autocomplete's, nearest first, then the well-known places
// it didn't suggest. The two are asked at once; without the well-known ones
// (Text Search failed), the nearby ones still come.
func (g *googlePlaces) suggestions(ctx context.Context, q string, near *[2]float64, lang, session string) ([]FoundPlace, error) {
	var (
		known    []FoundPlace
		knownErr error
		wg       sync.WaitGroup
	)
	wg.Go(func() { known, knownErr = g.wellKnown(ctx, q, near) })
	found, err := g.suggest(ctx, q, near, lang, session)
	wg.Wait()
	if err != nil {
		return nil, err
	}
	if knownErr != nil {
		slog.WarnContext(ctx, "google places text search failed; nearby suggestions only", "err", knownErr)
	}
	for _, k := range known {
		if !slices.ContainsFunc(found, func(f FoundPlace) bool { return f.PlaceID == k.PlaceID }) {
			found = append(found, k)
		}
	}
	return found, nil
}

// suggestHandler is GET /api/restaurants/suggest: Google's suggestions when
// it has a key and answers, Photon's otherwise. Photon gets no second
// search: it says nothing of how well known a place is, and unbiased it finds
// shops called "ラーメン" across Japan, not famous ones.
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
		found, err := h.google.suggestions(r.Context(), q, near, language(r), session)
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
