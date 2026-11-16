package restaurant

// A Google Maps place address names the place by a feature ID,
// "!1s0x601b209b09db8355:0xc0dbcebdcde7c6a0", whose first half is the S2
// cell (https://s2geometry.io) the place is in, at the finest level: about
// a centimetre. A short link to a named place (a bus stop, say) ends at such
// an address with no coordinates in it, so this is where its position is.
// Decoding follows s2geometry's CellID.ToFaceIJOrientation.

import (
	"math"
	"regexp"
	"strconv"
)

var featureID = regexp.MustCompile(`!1s0x([0-9a-f]{16}):0x[0-9a-f]{1,16}`)

const (
	s2LookupBits = 4
	s2SwapMask   = 1
	s2InvertMask = 2
	s2MaxLevel   = 30
)

var s2LookupIJ [1 << (2*s2LookupBits + 2)]int

func init() {
	posToIJ := [4][4]int{{0, 1, 3, 2}, {0, 2, 3, 1}, {3, 2, 0, 1}, {3, 1, 0, 2}}
	posToOrient := [4]int{s2SwapMask, 0, 0, s2InvertMask | s2SwapMask}
	var fill func(level, i, j, orig, pos, orient int)
	fill = func(level, i, j, orig, pos, orient int) {
		if level == s2LookupBits {
			ij := i<<s2LookupBits + j
			s2LookupIJ[pos<<2+orig] = ij<<2 + orient
			return
		}
		level, i, j, pos = level+1, i<<1, j<<1, pos<<2
		r := posToIJ[orient]
		for k := 0; k < 4; k++ {
			fill(level, i+r[k]>>1, j+r[k]&1, orig, pos+k, orient^posToOrient[k])
		}
	}
	for o := 0; o < 4; o++ {
		fill(0, 0, 0, o, 0, o)
	}
}

// featureCoords reads the position from a place address's feature ID.
func featureCoords(path string) (lat, lng *float64) {
	m := featureID.FindStringSubmatch(path)
	if m == nil {
		return nil, nil
	}
	id, err := strconv.ParseUint(m[1], 16, 64)
	if err != nil || id&1 == 0 { // only leaf cells (lowest bit set) are a precise spot
		return nil, nil
	}
	a, b, ok := s2LatLng(id)
	if !ok {
		return nil, nil
	}
	return &a, &b
}

// s2LatLng is the centre of a leaf S2 cell.
func s2LatLng(id uint64) (lat, lng float64, ok bool) {
	face := int(id >> 61)
	if face > 5 {
		return 0, 0, false
	}
	var i, j int
	bits := face & s2SwapMask
	for k := 7; k >= 0; k-- {
		n := 2 * s2LookupBits
		if k == 7 {
			n = 2 * (s2MaxLevel - 7*s2LookupBits)
		}
		bits += int(id>>(uint(k)*2*s2LookupBits+1)&(1<<n-1)) << 2
		bits = s2LookupIJ[bits]
		i += bits >> (s2LookupBits + 2) << (k * s2LookupBits)
		j += (bits >> 2 & (1<<s2LookupBits - 1)) << (k * s2LookupBits)
		bits &= s2SwapMask | s2InvertMask
	}
	stToUV := func(s float64) float64 {
		if s >= 0.5 {
			return (4*s*s - 1) / 3
		}
		return (1 - 4*(1-s)*(1-s)) / 3
	}
	u := stToUV((float64(i) + 0.5) / (1 << s2MaxLevel))
	v := stToUV((float64(j) + 0.5) / (1 << s2MaxLevel))
	var x, y, z float64
	switch face {
	case 0:
		x, y, z = 1, u, v
	case 1:
		x, y, z = -u, 1, v
	case 2:
		x, y, z = -u, -v, 1
	case 3:
		x, y, z = -1, -v, -u
	case 4:
		x, y, z = v, -1, -u
	default:
		x, y, z = v, u, -1
	}
	rad := 180 / math.Pi
	return math.Atan2(z, math.Hypot(x, y)) * rad, math.Atan2(y, x) * rad, true
}
