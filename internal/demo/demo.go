// Package demo fills a visitor's throwaway demo account with sample data,
// so they can see the app as it looks in use without making an account
// (DEMO, README.md). The account itself (no password, deleted after a day)
// is internal/account's; see its demo.go.
package demo

import (
	"context"
	"time"

	"github.com/rudnam/samfran/internal/restaurant"
	"github.com/rudnam/samfran/internal/space"
)

type sample struct {
	restaurant.Fields
	daysAgo int // when you went; 0 puts it on the want-to-go list
	stars   int
}

var samples = map[string][]sample{
	"en": {
		{Fields: restaurant.Fields{Name: "Hanabi Grill", Area: "Station East", Cuisine: "Hamburg steak", Notes: "Book ahead on weekends: the wait was two hours."}, daysAgo: 3, stars: 5},
		{Fields: restaurant.Fields{Name: "Gyoza Komachi", Area: "Station East", Cuisine: "Gyoza"}, daysAgo: 12, stars: 4},
		{Fields: restaurant.Fields{Name: "Unagi Mizuno", Area: "Lakeside", Cuisine: "Eel", Notes: "For a special occasion."}, daysAgo: 75, stars: 5},
		{Fields: restaurant.Fields{Name: "Ramen Tsubaki", Area: "Old Town", Cuisine: "Ramen"}, daysAgo: 20, stars: 3},
		{Fields: restaurant.Fields{Name: "Blue Sky Café", Area: "Riverside", Cuisine: "Café", Notes: "Their pancakes are supposed to be great."}},
		{Fields: restaurant.Fields{Name: "Taco Loco", Area: "Station East", Cuisine: "Mexican"}},
		{Fields: restaurant.Fields{Name: "Castle Park", Area: "Old Town", Cuisine: "Park", Category: restaurant.CategoryNature, Notes: "The cherry trees, in early April."}, daysAgo: 30, stars: 4},
		{Fields: restaurant.Fields{Name: "Hilltop Shrine", Area: "North Hills", Cuisine: "Shrine", Category: restaurant.CategoryCulture}},
		{Fields: restaurant.Fields{Name: "Lakeside Onsen", Area: "Lakeside", Cuisine: "Hot spring", Category: restaurant.CategoryRelax, Notes: "Open until midnight."}},
	},
	"ja": {
		{Fields: restaurant.Fields{Name: "はなび亭", Area: "駅東", Cuisine: "ハンバーグ", Notes: "週末は2時間待ち。早めに行く。"}, daysAgo: 3, stars: 5},
		{Fields: restaurant.Fields{Name: "餃子小町", Area: "駅東", Cuisine: "餃子"}, daysAgo: 12, stars: 4},
		{Fields: restaurant.Fields{Name: "うなぎ水野", Area: "湖畔", Cuisine: "うなぎ", Notes: "特別な日に。"}, daysAgo: 75, stars: 5},
		{Fields: restaurant.Fields{Name: "ラーメン椿", Area: "旧市街", Cuisine: "ラーメン"}, daysAgo: 20, stars: 3},
		{Fields: restaurant.Fields{Name: "ブルースカイカフェ", Area: "川沿い", Cuisine: "カフェ", Notes: "パンケーキがおいしいらしい。"}},
		{Fields: restaurant.Fields{Name: "タコロコ", Area: "駅東", Cuisine: "メキシコ料理"}},
		{Fields: restaurant.Fields{Name: "城山公園", Area: "旧市街", Cuisine: "公園", Category: restaurant.CategoryNature, Notes: "桜は4月の初め。"}, daysAgo: 30, stars: 4},
		{Fields: restaurant.Fields{Name: "丘の上神社", Area: "北の丘", Cuisine: "神社", Category: restaurant.CategoryCulture}},
		{Fields: restaurant.Fields{Name: "湖畔温泉", Area: "湖畔", Cuisine: "温泉", Category: restaurant.CategoryRelax, Notes: "夜12時まで。"}},
	},
}

// Filler returns the function that fills a new demo account, in lang ("en"
// or "ja"). zone is the visitor's time zone, for the visits' dates.
func Filler(spaces *space.Store, restaurants *restaurant.Store) func(ctx context.Context, userID int64, lang string, zone *time.Location) error {
	return func(ctx context.Context, userID int64, lang string, zone *time.Location) error {
		list, ok := samples[lang]
		if !ok {
			list = samples["en"]
		}
		sp, err := spaces.Of(ctx, userID)
		if err != nil {
			return err
		}
		today := time.Now().In(zone)
		for _, s := range list {
			id, err := restaurants.Create(ctx, sp, userID, s.Fields)
			if err != nil {
				return err
			}
			if s.daysAgo > 0 {
				if err := restaurants.AddVisit(ctx, sp, userID, id, today.AddDate(0, 0, -s.daysAgo).Format(time.DateOnly), ""); err != nil {
					return err
				}
			}
			if s.stars > 0 {
				if err := restaurants.Rate(ctx, sp, userID, id, s.stars); err != nil {
					return err
				}
			}
		}
		return nil
	}
}
