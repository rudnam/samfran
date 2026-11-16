// Package geo has the arithmetic for positions on the map.
package geo

import "math"

// Meters is the distance between two points (haversine).
func Meters(lat1, lng1, lat2, lng2 float64) float64 {
	const r = 6371000.0
	rad := math.Pi / 180
	dLat, dLng := (lat2-lat1)*rad, (lng2-lng1)*rad
	h := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dLng/2)*math.Sin(dLng/2)
	return 2 * r * math.Asin(math.Sqrt(h))
}
