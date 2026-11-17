package restaurant

// Categories: each place is in one, by why you'd go there. A café inside a
// mall is for eating; a shrine in a park is for the shrine; an aquarium is
// for fun. The search picks one from the kind of place (Google's place type,
// or OpenStreetMap's tag), and people can change it.

import (
	"slices"
	"strings"
)

const (
	CategoryEat      = "eat"      // restaurants, cafés, bars, bakeries, sweets
	CategoryNature   = "nature"   // parks, gardens, beaches, mountains, hikes
	CategoryCulture  = "culture"  // shrines, temples, castles, museums, galleries
	CategoryFun      = "fun"      // theme parks, aquariums, zoos, arcades, sports, shows
	CategoryRelax    = "relax"    // onsen, sentō, saunas, spas
	CategoryShopping = "shopping" // malls, markets, outlets, shops
	CategoryOther    = "other"
)

// Categories are all of them, in the order the app shows them.
var Categories = []string{CategoryEat, CategoryNature, CategoryCulture, CategoryFun, CategoryRelax, CategoryShopping, CategoryOther}

// googleCategories are Google place types (Places API (New), "Table A") by
// category, in the order a place with several types is sorted: a shrine in
// a park is a shrine, an onsen with a restaurant is an onsen.
var googleCategories = []struct {
	category string
	types    []string
}{
	{CategoryRelax, []string{"spa", "sauna", "public_bath", "hot_spring", "japanese_inn", "massage", "wellness_center"}},
	{CategoryFun, []string{"amusement_park", "amusement_center", "aquarium", "zoo", "wildlife_park", "water_park",
		"bowling_alley", "movie_theater", "karaoke", "night_club", "casino", "video_arcade", "stadium", "arena",
		"sports_complex", "ski_resort", "golf_course", "ice_skating_rink", "observation_deck", "planetarium",
		"performing_arts_theater", "concert_hall", "event_venue", "go_karting_venue", "adventure_sports_center",
		"cycling_park", "roller_coaster", "ferris_wheel", "playground", "sports_activity_location", "fishing_pond",
		"skateboard_park", "comedy_club", "opera_house", "amphitheatre"}},
	{CategoryCulture, []string{"museum", "art_gallery", "historical_landmark", "historical_place", "monument",
		"cultural_landmark", "shinto_shrine", "buddhist_temple", "place_of_worship", "church", "hindu_temple",
		"mosque", "synagogue", "castle", "cultural_center", "sculpture", "art_studio"}},
	{CategoryEat, []string{"food", "restaurant", "cafe", "coffee_shop", "bar", "pub", "wine_bar", "bakery",
		"ice_cream_shop", "dessert_shop", "confectionery", "meal_takeaway", "meal_delivery", "cafeteria",
		"food_court", "tea_house", "juice_shop", "donut_shop", "bagel_shop", "candy_store", "chocolate_shop",
		"sandwich_shop", "steak_house", "diner", "bar_and_grill", "deli", "acai_shop", "cat_cafe", "dog_cafe",
		"pastry_shop", "brewery", "winery", "beer_garden"}},
	{CategoryNature, []string{"park", "national_park", "state_park", "city_park", "garden", "botanical_garden",
		"hiking_area", "beach", "campground", "picnic_ground", "natural_feature", "dog_park", "lake",
		"mountain_peak", "nature_preserve", "woods", "river", "island", "scenic_spot", "marina"}},
	{CategoryShopping, []string{"shopping_mall", "market", "department_store", "supermarket", "flea_market",
		"farmers_market", "gift_shop", "book_store", "clothing_store", "store", "outlet_mall"}},
}

// googleCategory is the category for a place with these Google types, which
// come in no particular order: the first category in googleCategories that
// any of them is in, else what a type's name says ("…_restaurant" is for
// eating), else a tourist attraction is culture, else other.
func googleCategory(types []string) string {
	for _, c := range googleCategories {
		for _, t := range types {
			if slices.Contains(c.types, t) {
				return c.category
			}
		}
	}
	for _, t := range types {
		switch {
		case strings.HasSuffix(t, "_restaurant"):
			return CategoryEat
		case strings.HasSuffix(t, "_store") || strings.HasSuffix(t, "_shop"):
			return CategoryShopping
		}
	}
	// Most often a shrine, a temple or a castle, whose own type Google left out.
	if slices.Contains(types, "tourist_attraction") {
		return CategoryCulture
	}
	return CategoryOther
}

// osmCategories are OpenStreetMap tags (key:value, or key: for all of a
// key) by category, as Photon reports them.
var osmCategories = map[string]string{
	"amenity:restaurant": CategoryEat, "amenity:fast_food": CategoryEat, "amenity:cafe": CategoryEat,
	"amenity:bar": CategoryEat, "amenity:pub": CategoryEat, "amenity:food_court": CategoryEat,
	"amenity:ice_cream": CategoryEat, "amenity:biergarten": CategoryEat,
	"shop:bakery": CategoryEat, "shop:confectionery": CategoryEat, "shop:pastry": CategoryEat,

	"leisure:park": CategoryNature, "leisure:garden": CategoryNature, "leisure:nature_reserve": CategoryNature,
	"leisure:beach_resort": CategoryNature, "boundary:national_park": CategoryNature,
	"tourism:viewpoint": CategoryNature, "tourism:camp_site": CategoryNature, "tourism:picnic_site": CategoryNature,
	"natural:": CategoryNature,

	"tourism:museum": CategoryCulture, "tourism:gallery": CategoryCulture, "tourism:attraction": CategoryCulture,
	"amenity:place_of_worship": CategoryCulture, "historic:": CategoryCulture,

	"tourism:zoo": CategoryFun, "tourism:aquarium": CategoryFun, "tourism:theme_park": CategoryFun,
	"leisure:water_park": CategoryFun, "leisure:sports_centre": CategoryFun, "leisure:bowling_alley": CategoryFun,
	"leisure:amusement_arcade": CategoryFun, "leisure:stadium": CategoryFun, "leisure:golf_course": CategoryFun,
	"leisure:ice_rink": CategoryFun, "amenity:cinema": CategoryFun, "amenity:theatre": CategoryFun,
	"amenity:karaoke_box": CategoryFun, "amenity:planetarium": CategoryFun,

	"amenity:public_bath": CategoryRelax, "leisure:sauna": CategoryRelax, "natural:hot_spring": CategoryRelax,

	"shop:mall": CategoryShopping, "shop:department_store": CategoryShopping, "amenity:marketplace": CategoryShopping,
}

// photonTags are the tags the search asks Photon for: every kind of place
// above, and nothing else (no streets, stations or towns).
var photonTags = func() []string {
	tags := make([]string, 0, len(osmCategories))
	for t := range osmCategories {
		tags = append(tags, strings.TrimSuffix(t, ":")) // "natural:" → "natural", the whole key
	}
	slices.Sort(tags)
	return tags
}()

// osmCategory is the category for an OpenStreetMap tag.
func osmCategory(key, value string) string {
	if c, ok := osmCategories[key+":"+value]; ok {
		return c
	}
	if c, ok := osmCategories[key+":"]; ok {
		return c
	}
	return CategoryOther
}

// validCategory reports whether c is one of Categories.
func validCategory(c string) bool { return slices.Contains(Categories, c) }
