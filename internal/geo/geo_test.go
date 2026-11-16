package geo

import "testing"

func TestMeters(t *testing.T) {
	// The two 葵西小学校入口 stops, one each side of the road: about 30 m.
	if d := Meters(34.7695178, 137.7123915, 34.7696621, 137.7120673); d < 25 || d > 40 {
		t.Errorf("got %v m", d)
	}
}
