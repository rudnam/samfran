package restaurant

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// fakeGoogle answers like Places API (New), and records what it was asked.
func fakeGoogle(t *testing.T, fail bool) (*httptest.Server, *[]*http.Request, *[]map[string]any) {
	var reqs []*http.Request
	var bodies []map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reqs = append(reqs, r)
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		bodies = append(bodies, body)
		if fail || r.Header.Get("X-Goog-Api-Key") != "k" {
			http.Error(w, `{"error":{"code":403}}`, http.StatusForbidden)
			return
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/places:autocomplete":
			w.Write([]byte(`{"suggestions":[
				{"placePrediction":{"placeId":"ChIJishimatsu1","structuredFormat":{"mainText":{"text":"石松 JR浜松駅店"},"secondaryText":{"text":"日本、〒430-0926 静岡県浜松市中央区砂山町"}},"types":["food","chinese_restaurant","restaurant"]}},
				{"queryPrediction":{"text":{"text":"石松餃子"}}},
				{"placePrediction":{"placeId":"ChIJishimatsu2","structuredFormat":{"mainText":{"text":"石松餃子 浜北本店"},"secondaryText":{"text":"日本、静岡県浜松市浜名区"}},"types":["restaurant","food"]}}
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
	return srv, &reqs, &bodies
}

func TestGoogleSuggestAndDetails(t *testing.T) {
	srv, reqs, bodies := fakeGoogle(t, false)
	h := NewHandler(nil)
	h.UseGoogle("k", srv.URL+"/")

	rec := httptest.NewRecorder()
	err := h.suggestHandler(rec, httptest.NewRequest("GET", "/api/restaurants/suggest?q=ishimatsu&lat=34.70&lng=137.73&lang=ja&session=abc-123", nil))
	if err != nil {
		t.Fatal(err)
	}
	var found []FoundPlace
	json.Unmarshal(rec.Body.Bytes(), &found)
	if len(found) != 2 {
		t.Fatalf("found %+v", found)
	}
	if f := found[0]; f.Name != "石松 JR浜松駅店" || f.PlaceID != "ChIJishimatsu1" || f.Street != "静岡県浜松市中央区砂山町" || f.Kind != "chinese_restaurant" {
		t.Errorf("first: %+v", f)
	}
	b := (*bodies)[0]
	if b["input"] != "ishimatsu" || b["languageCode"] != "ja" || b["sessionToken"] != "abc-123" || b["locationBias"] == nil {
		t.Errorf("autocomplete body: %v", b)
	}

	rec = httptest.NewRecorder()
	r := httptest.NewRequest("GET", "/api/restaurants/place/ChIJishimatsu1?lang=ja&session=abc-123", nil)
	r.SetPathValue("id", "ChIJishimatsu1")
	if err := h.placeHandler(rec, r); err != nil {
		t.Fatal(err)
	}
	var p FoundPlace
	json.Unmarshal(rec.Body.Bytes(), &p)
	want := FoundPlace{Name: "石松 JR浜松駅店", Area: "浜松市 中央区", Street: "砂山町", Kind: "chinese_restaurant", Lat: 34.7036, Lng: 137.7352,
		PlaceID: "ChIJishimatsu1", Food: "中華料理店", MapURL: "https://maps.google.com/?cid=123"}
	if p != want {
		t.Errorf("details:\n got %+v\nwant %+v", p, want)
	}
	d := (*reqs)[1]
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
	if lang := (*reqs)[2].URL.Query().Get("languageCode"); lang != "ja" {
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
	google, _, _ := fakeGoogle(t, true)
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
	} {
		if got := suggestedKind(types); got != want {
			t.Errorf("suggestedKind(%v) = %q, want %q", types, got, want)
		}
	}
}
