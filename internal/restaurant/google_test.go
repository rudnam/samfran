package restaurant

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
)

// googleCall is a request the fake Google was sent.
type googleCall struct {
	r    *http.Request
	body map[string]any
}

// googleCalls are the requests the fake Google was sent, by path: suggest
// sends two at once, in no set order.
type googleCalls struct {
	mu    sync.Mutex
	calls map[string][]googleCall
}

// to are the requests sent to path ("/places:autocomplete"), in order.
func (c *googleCalls) to(path string) []googleCall {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Clone(c.calls[path])
}

// fakeGoogle answers like Places API (New), and records what it was asked.
// Text Search finds 鎌倉大仏殿高徳院 (well known), 東大寺大仏殿 (just), a
// small temple and 大仏殿跡 (not), and 石松 JR浜松駅店, Autocomplete's first.
func fakeGoogle(t *testing.T, fail bool) (*httptest.Server, *googleCalls) {
	calls := &googleCalls{calls: map[string][]googleCall{}}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		calls.mu.Lock()
		calls.calls[r.URL.Path] = append(calls.calls[r.URL.Path], googleCall{r, body})
		calls.mu.Unlock()
		if fail || r.Header.Get("X-Goog-Api-Key") != "k" {
			http.Error(w, `{"error":{"code":403}}`, http.StatusForbidden)
			return
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/places:autocomplete":
			w.Write([]byte(`{"suggestions":[
				{"placePrediction":{"placeId":"ChIJishimatsu1","structuredFormat":{"mainText":{"text":"石松 JR浜松駅店"},"secondaryText":{"text":"日本、〒430-0926 静岡県浜松市中央区砂山町"}},"types":["food","chinese_restaurant","restaurant"],"distanceMeters":412}},
				{"queryPrediction":{"text":{"text":"石松餃子"}}},
				{"placePrediction":{"placeId":"ChIJishimatsu2","structuredFormat":{"mainText":{"text":"石松餃子 浜北本店"},"secondaryText":{"text":"日本、静岡県浜松市浜名区"}},"types":["restaurant","food"]}},
				{"placePrediction":{"placeId":"ChIJishimatsucho","structuredFormat":{"mainText":{"text":"石松町"},"secondaryText":{"text":"日本、静岡県"}},"types":["sublocality","political","geocode"]}},
				{"placePrediction":{"placeId":"ChIJishimatsupark","structuredFormat":{"mainText":{"text":"石松公園"},"secondaryText":{"text":"日本、静岡県浜松市"}},"types":["park","tourist_attraction","point_of_interest","establishment"]}}
			]}`))
		case r.Method == http.MethodPost && r.URL.Path == "/places:searchText":
			w.Write([]byte(`{"places":[
				{"id":"ChIJsmalltemple","displayName":{"text":"大仏寺"},"shortFormattedAddress":"高岡市","types":["buddhist_temple","place_of_worship"],"primaryType":"buddhist_temple","location":{"latitude":36.75,"longitude":137.02},"userRatingCount":120},
				{"id":"ChIJtodaiji000","displayName":{"text":"東大寺大仏殿"},"shortFormattedAddress":"奈良市雑司町４０６−１","types":["buddhist_temple","tourist_attraction"],"primaryType":"buddhist_temple","location":{"latitude":34.689,"longitude":135.84},"userRatingCount":3000},
				{"id":"ChIJishimatsu1","displayName":{"text":"石松 JR浜松駅店"},"shortFormattedAddress":"浜松市中央区砂山町","types":["chinese_restaurant","restaurant"],"primaryType":"chinese_restaurant","location":{"latitude":34.7036,"longitude":137.7352},"userRatingCount":9000},
				{"id":"ChIJruins00000","displayName":{"text":"鎌倉大仏殿跡"},"shortFormattedAddress":"鎌倉市長谷４丁目２−２８","types":["historical_place"],"primaryType":"historical_place","location":{"latitude":35.317,"longitude":139.536},"userRatingCount":2999},
				{"id":"ChIJkotokuin00","displayName":{"text":"鎌倉大仏殿高徳院"},"shortFormattedAddress":"鎌倉市長谷４丁目２−２８","types":["buddhist_temple","tourist_attraction","place_of_worship"],"primaryType":"buddhist_temple","location":{"latitude":35.3167,"longitude":139.5358},"userRatingCount":33716}
			]}`))
		case r.Method == http.MethodGet && r.URL.Path == "/places/ChIJishimatsu1":
			w.Write([]byte(`{"id":"ChIJishimatsu1","displayName":{"text":"石松 JR浜松駅店"},
				"addressComponents":[
					{"longText":"砂山町","types":["sublocality_level_2","sublocality","political"]},
					{"longText":"中央区","types":["sublocality_level_1","sublocality","political"]},
					{"longText":"浜松市","types":["locality","political"]},
					{"longText":"静岡県","types":["administrative_area_level_1","political"]}],
				"location":{"latitude":34.7036,"longitude":137.7352},
				"primaryType":"chinese_restaurant","primaryTypeDisplayName":{"text":"中華料理店"},
				"googleMapsUri":"https://maps.google.com/?cid=123&g_mp=CiVnb29nbGU"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/places/ChIJishimatsu2":
			w.Write([]byte(`{"id":"ChIJishimatsu2","displayName":{"text":"石松餃子 浜北本店"},
				"addressComponents":[{"longText":"浜松市","types":["locality","political"]}],
				"location":{"latitude":34.79,"longitude":137.79},
				"primaryType":"restaurant","primaryTypeDisplayName":{"text":"レストラン"},
				"googleMapsUri":"https://maps.google.com/?cid=456"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return srv, calls
}

func TestGoogleSuggestAndDetails(t *testing.T) {
	srv, calls := fakeGoogle(t, false)
	h := NewHandler(nil)
	h.UseGoogle("k", srv.URL+"/")

	rec := httptest.NewRecorder()
	err := h.suggestHandler(rec, httptest.NewRequest("GET", "/api/restaurants/suggest?q=ishimatsu&lat=34.70&lng=137.73&lang=ja&session=abc-123", nil))
	if err != nil {
		t.Fatal(err)
	}
	var found []FoundPlace
	json.Unmarshal(rec.Body.Bytes(), &found)
	// The town is left out: it's an address, not a place to go. The nearby
	// one, the well-known places Autocomplete didn't suggest, then the
	// suggestions with no distance.
	if len(found) != 5 {
		t.Fatalf("found %+v", found)
	}
	if f := found[0]; f.Name != "石松 JR浜松駅店" || f.PlaceID != "ChIJishimatsu1" || f.Street != "静岡県浜松市中央区砂山町" || f.Kind != "chinese_restaurant" || f.Category != CategoryEat ||
		f.Meters == nil || *f.Meters != 412 || f.Source != SourceGoogle {
		t.Errorf("first: %+v", f)
	}
	if f := found[3]; f.Name != "石松餃子 浜北本店" || f.Meters != nil {
		t.Errorf("a distance Google didn't give: %+v", f)
	}
	if f := found[4]; f.Name != "石松公園" || f.Kind != "park" || f.Category != CategoryNature {
		t.Errorf("park: %+v", f)
	}
	b := calls.to("/places:autocomplete")[0].body
	if _, ok := b["includedPrimaryTypes"]; ok {
		t.Error("suggestions are still limited to some types of place")
	}
	if b["input"] != "ishimatsu" || b["languageCode"] != "ja" || b["sessionToken"] != "abc-123" || b["locationBias"] == nil {
		t.Errorf("autocomplete body: %v", b)
	}
	// The well-known ones, the most reviewed first: 高徳院 178 km away.
	if f := found[1]; f.Name != "鎌倉大仏殿高徳院" || f.PlaceID != "ChIJkotokuin00" || f.Street != "鎌倉市長谷４丁目２−２８" || f.Kind != "buddhist_temple" ||
		f.Category != CategoryCulture || f.Meters == nil || *f.Meters/1000 != 178 || f.Source != SourceGoogle || f.Lat != 0 {
		t.Errorf("well known: %+v", f)
	}
	if f := found[2]; f.Name != "東大寺大仏殿" {
		t.Errorf("second well known: %+v", f)
	}
	// Text Search is asked anywhere in Japan, not near the person.
	ts := calls.to("/places:searchText")[0]
	if b := ts.body; b["textQuery"] != "ishimatsu" || b["locationBias"] != nil || b["locationRestriction"] == nil || b["languageCode"] != "ja" {
		t.Errorf("text search body: %v", b)
	}
	if !strings.Contains(ts.r.Header.Get("X-Goog-FieldMask"), "places.userRatingCount") {
		t.Errorf("text search field mask: %v", ts.r.Header)
	}

	rec = httptest.NewRecorder()
	r := httptest.NewRequest("GET", "/api/restaurants/place/ChIJishimatsu1?lang=ja&session=abc-123", nil)
	r.SetPathValue("id", "ChIJishimatsu1")
	if err := h.placeHandler(rec, r); err != nil {
		t.Fatal(err)
	}
	var p FoundPlace
	json.Unmarshal(rec.Body.Bytes(), &p)
	want := FoundPlace{Name: "石松 JR浜松駅店", Area: "浜松市 中央区", Street: "砂山町", Kind: "chinese_restaurant", Category: CategoryEat, Lat: 34.7036, Lng: 137.7352,
		PlaceID: "ChIJishimatsu1", Food: "中華料理店", MapURL: "https://maps.google.com/?cid=123", Source: SourceGoogle}
	if p != want {
		t.Errorf("details:\n got %+v\nwant %+v", p, want)
	}
	d := calls.to("/places/ChIJishimatsu1")[0].r
	if d.URL.Query().Get("sessionToken") != "abc-123" || d.URL.Query().Get("languageCode") != "ja" || !strings.Contains(d.Header.Get("X-Goog-FieldMask"), "primaryTypeDisplayName") {
		t.Errorf("details request: %s %v", d.URL, d.Header)
	}

	// A plain restaurant: Food stays empty. The Android app says its language in Accept-Language.
	rec = httptest.NewRecorder()
	r = httptest.NewRequest("GET", "/api/restaurants/place/ChIJishimatsu2", nil)
	r.Header.Set("Accept-Language", "ja-JP,en;q=0.8")
	r.SetPathValue("id", "ChIJishimatsu2")
	if err := h.placeHandler(rec, r); err != nil {
		t.Fatal(err)
	}
	p = FoundPlace{}
	json.Unmarshal(rec.Body.Bytes(), &p)
	if p.Food != "" || p.Area != "浜松市" {
		t.Errorf("plain restaurant: %+v", p)
	}
	if lang := calls.to("/places/ChIJishimatsu2")[0].r.URL.Query().Get("languageCode"); lang != "ja" {
		t.Errorf("languageCode from Accept-Language = %q", lang)
	}

	// Anything but a place ID isn't sent to Google.
	r = httptest.NewRequest("GET", "/api/restaurants/place/x", nil)
	r.SetPathValue("id", "../places:autocomplete")
	if err := h.placeHandler(httptest.NewRecorder(), r); err == nil {
		t.Error("a bad place ID was accepted")
	}
}

func TestSuggestFallsBackToPhoton(t *testing.T) {
	google, _ := fakeGoogle(t, true)
	photon := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"features":[{"geometry":{"coordinates":[137.734,34.704]},"properties":{"name":"すき家","osm_value":"fast_food","city":"浜松市"}}]}`))
	}))
	defer photon.Close()

	for _, key := range []string{"", "k"} { // no key; Google failing
		h := NewHandler(nil)
		h.search = &placeSearch{base: photon.URL, client: photon.Client()}
		h.UseGoogle(key, google.URL+"/")
		rec := httptest.NewRecorder()
		if err := h.suggestHandler(rec, httptest.NewRequest("GET", "/api/restaurants/suggest?q=sukiya", nil)); err != nil {
			t.Fatal(err)
		}
		var found []FoundPlace
		json.Unmarshal(rec.Body.Bytes(), &found)
		if len(found) != 1 || found[0].Name != "すき家" || found[0].PlaceID != "" || found[0].Lat != 34.704 {
			t.Errorf("key %q: %+v", key, found)
		}
	}

	// Without a key there are no details to ask for.
	h := NewHandler(nil)
	r := httptest.NewRequest("GET", "/api/restaurants/place/ChIJishimatsu1", nil)
	r.SetPathValue("id", "ChIJishimatsu1")
	if err := h.placeHandler(httptest.NewRecorder(), r); err == nil {
		t.Error("details without a key")
	}
}

func TestGoogleCategory(t *testing.T) {
	for _, c := range []struct {
		want  string
		types []string
	}{
		{CategoryEat, []string{"ramen_restaurant", "food", "point_of_interest"}},
		{CategoryEat, []string{"store", "cafe", "food"}},                          // a café in a shop
		{CategoryEat, []string{"okonomiyaki_restaurant"}},                         // by its name
		{CategoryNature, []string{"tourist_attraction", "park", "establishment"}}, // the park, not "attraction"
		{CategoryCulture, []string{"park", "shinto_shrine", "place_of_worship"}},  // a shrine in a park
		{CategoryCulture, []string{"tourist_attraction", "point_of_interest"}},
		{CategoryFun, []string{"aquarium", "tourist_attraction", "museum"}},
		{CategoryRelax, []string{"restaurant", "public_bath", "spa"}}, // an onsen with a restaurant
		{CategoryShopping, []string{"shopping_mall", "point_of_interest"}},
		{CategoryShopping, []string{"electronics_store"}},
		{CategoryOther, []string{"point_of_interest", "establishment"}},
		{CategoryOther, nil},
	} {
		if got := googleCategory(c.types); got != c.want {
			t.Errorf("googleCategory(%v) = %q, want %q", c.types, got, c.want)
		}
	}
}

func TestOSMCategory(t *testing.T) {
	for _, c := range []struct{ key, value, want string }{
		{"amenity", "cafe", CategoryEat},
		{"leisure", "park", CategoryNature},
		{"natural", "beach", CategoryNature},     // the whole key
		{"natural", "hot_spring", CategoryRelax}, // but this one value
		{"historic", "castle", CategoryCulture},
		{"amenity", "place_of_worship", CategoryCulture},
		{"tourism", "aquarium", CategoryFun},
		{"shop", "mall", CategoryShopping},
		{"tourism", "hotel", CategoryOther},
	} {
		if got := osmCategory(c.key, c.value); got != c.want {
			t.Errorf("osmCategory(%s, %s) = %q, want %q", c.key, c.value, got, c.want)
		}
	}
}

func TestShortAddress(t *testing.T) {
	for in, want := range map[string]string{
		"日本、〒430-0926 静岡県浜松市中央区砂山町":                          "静岡県浜松市中央区砂山町",
		"日本、静岡県浜松市浜名区":                                       "静岡県浜松市浜名区",
		"Sunayamacho, Chuo Ward, Hamamatsu, Shizuoka, Japan": "Sunayamacho, Chuo Ward, Hamamatsu, Shizuoka",
	} {
		if got := shortAddress(in); got != want {
			t.Errorf("shortAddress(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSuggestedKind(t *testing.T) {
	for want, types := range map[string][]string{
		"ramen_restaurant":    {"food", "point_of_interest", "ramen_restaurant", "cafe"},
		"cafe":                {"store", "cafe", "food"},
		"dumpling_restaurant": {"food", "dumpling_restaurant", "restaurant"},
		"restaurant":          {"food", "establishment"},
		"park":                {"point_of_interest", "park", "establishment"},
	} {
		if got := suggestedKind(types); got != want {
			t.Errorf("suggestedKind(%v) = %q, want %q", types, got, want)
		}
	}
}

func TestDetailsAlwaysInJapaneseFoodInTheAppsLanguage(t *testing.T) {
	srv, calls := fakeGoogle(t, false)
	h := NewHandler(nil)
	h.UseGoogle("k", srv.URL+"/")

	// Someone using the app in English.
	rec := httptest.NewRecorder()
	r := httptest.NewRequest("GET", "/api/restaurants/place/ChIJishimatsu1?lang=en", nil)
	r.SetPathValue("id", "ChIJishimatsu1")
	if err := h.placeHandler(rec, r); err != nil {
		t.Fatal(err)
	}
	var p FoundPlace
	json.Unmarshal(rec.Body.Bytes(), &p)
	if p.Name != "石松 JR浜松駅店" || p.Area != "浜松市 中央区" || p.Food != "Chinese restaurant" {
		t.Errorf("in English: %+v", p)
	}
	if lang := calls.to("/places/ChIJishimatsu1")[0].r.URL.Query().Get("languageCode"); lang != "ja" {
		t.Errorf("details asked in %q, want ja", lang)
	}

	rec = httptest.NewRecorder()
	h.suggestHandler(rec, httptest.NewRequest("GET", "/api/restaurants/suggest?q=ishimatsu&lang=en", nil))
	for _, path := range []string{"/places:autocomplete", "/places:searchText"} {
		if c := calls.to(path); len(c) != 1 || c[0].body["languageCode"] != "ja" {
			t.Errorf("%s asked in %v, want ja", path, c)
		}
	}
}

func TestSuggestWithoutWellKnown(t *testing.T) {
	// Text Search failing: the nearby suggestions still come, from Google.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/places:autocomplete" {
			http.Error(w, "{}", http.StatusInternalServerError)
			return
		}
		w.Write([]byte(`{"suggestions":[{"placePrediction":{"placeId":"ChIJishimatsu1","structuredFormat":{"mainText":{"text":"石松 JR浜松駅店"}},"types":["restaurant"]}}]}`))
	}))
	defer srv.Close()
	h := NewHandler(nil)
	h.UseGoogle("k", srv.URL+"/")
	rec := httptest.NewRecorder()
	if err := h.suggestHandler(rec, httptest.NewRequest("GET", "/api/restaurants/suggest?q=ishimatsu", nil)); err != nil {
		t.Fatal(err)
	}
	var found []FoundPlace
	json.Unmarshal(rec.Body.Bytes(), &found)
	if len(found) != 1 || found[0].PlaceID != "ChIJishimatsu1" {
		t.Errorf("found %+v", found)
	}
}

func TestOrdered(t *testing.T) {
	at := func(name string, km float64) FoundPlace {
		m := int(km * 1000)
		return FoundPlace{Name: name, PlaceID: "id-" + name, Meters: &m, Category: CategoryCulture}
	}
	eat := func(name string, km float64) FoundPlace {
		f := at(name, km)
		f.Category = CategoryEat
		return f
	}
	names := func(ps []FoundPlace) string {
		var n []string
		for _, p := range ps {
			n = append(n, p.Name)
		}
		return strings.Join(n, ", ")
	}
	for _, c := range []struct {
		q                string
		suggested, known []FoundPlace
		want             string
	}{
		// Nearest first, whatever order Google gave.
		{"ラーメン", []FoundPlace{at("Q", 1.9), at("三太", 0.5), at("山まさか", 4.4), at("驍", 1.3)}, nil, "三太, 驍, Q, 山まさか"},
		// Nothing near: the well-known places before what's 100 km away.
		{"大仏", []FoundPlace{at("野球場", 100.6), at("大仏殿", 172.8)}, []FoundPlace{at("高徳院", 178), at("牛久大仏", 267)}, "高徳院, 牛久大仏, 野球場, 大仏殿"},
		// The place named just what was typed comes first among the near ones,
		// not a road marker nearer by; 20 km is still near.
		{"舘山寺", []FoundPlace{at("舘山寺", 13.1), at("舘山寺街道道標", 4.1), at("舘山寺温泉", 12.5)}, nil, "舘山寺, 舘山寺街道道標, 舘山寺温泉"},
		{"浜松城", []FoundPlace{at("浜松城出丸跡", 1.1), at("浜松城 ", 1.4)}, nil, "浜松城 , 浜松城出丸跡"},
		// Not a place to eat: one so named is likely a chain's branch.
		{"sukiya", []FoundPlace{eat("Sukiya 鍛冶町", 0.7), eat("Sukiya", 5.4), eat("Sukiya 駅南", 0.5)}, nil, "Sukiya 駅南, Sukiya 鍛冶町, Sukiya"},
		// A well-known place Autocomplete also suggested comes once, where Autocomplete put it.
		{"鎌倉大仏", []FoundPlace{at("高徳院", 178), at("大仏殿跡", 178)}, []FoundPlace{at("高徳院", 178)}, "高徳院, 大仏殿跡"},
		{"一蘭", []FoundPlace{at("一蘭 浜松店", 4.9), at("一蘭 豊橋店", 34.3), at("一蘭 新東名", 19.9)}, []FoundPlace{at("一蘭 上野", 230)},
			"一蘭 浜松店, 一蘭 新東名, 一蘭 上野, 一蘭 豊橋店"},
		// A well-known place nearby is among the nearby ones, by distance.
		{"浜松", []FoundPlace{at("浜松駅", 0.6), at("イオンモール浜松市野", 5.4)}, []FoundPlace{at("NEOPASA浜松", 16), at("浜松城", 1.4)}, "浜松駅, 浜松城, イオンモール浜松市野, NEOPASA浜松"},
	} {
		if got := names(ordered(c.q, c.suggested, c.known)); got != c.want {
			t.Errorf("%s: got %s, want %s", c.q, got, c.want)
		}
	}
}
