package restaurant

import (
	"testing"

	"github.com/rudnam/samfran/internal/geo"
)

func TestFeatureCoords(t *testing.T) {
	for _, c := range []struct {
		path     string
		lat, lng float64 // where OpenStreetMap has it
	}{
		// 葵西小学校入口 (bus stop, Hamamatsu)
		{"/maps/place/x/data=!4m2!3m1!1s0x601b209b09db8355:0xc0dbcebdcde7c6a0!18m1!1e1", 34.76966, 137.71207},
		// Kitashimbayashi (北新林, a stop on 安城知立線, Chiryū)
		{"/maps/place/Kitashimbayashi/data=!4m2!3m1!1s0x60049c11f0d8fdcb:0xa680fa9bdff67690!18m1!1e1", 34.99196, 137.04406},
	} {
		lat, lng := featureCoords(c.path)
		if lat == nil {
			t.Errorf("%s: no position", c.path)
			continue
		}
		if d := geo.Meters(*lat, *lng, c.lat, c.lng); d > 30 {
			t.Errorf("%s: %v,%v is %.0f m off", c.path, *lat, *lng, d)
		}
	}
	if lat, _ := featureCoords("/maps/place/x/data=!4m2!3m1!1s0x0:0x1"); lat != nil {
		t.Errorf("a zero feature ID gave %v", *lat)
	}
}
