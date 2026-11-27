package server_test

import (
	"bytes"
	"io"
	"math/rand/v2"
	"mime/multipart"
	"net/http"
	"testing"
)

// jpegOf is size bytes that read as a JPEG: its signature, then noise.
func jpegOf(size int) []byte {
	b := make([]byte, size)
	for i := range b {
		b[i] = byte(rand.IntN(256))
	}
	copy(b, "\xFF\xD8\xFF\xE0")
	return b
}

// photoForm is a photo upload: fields in the order given, each a name and its bytes.
func photoForm(fields ...[2][]byte) ([]byte, string) {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	for _, f := range fields {
		name := string(f[0])
		var w io.Writer
		if name == "photo" || name == "thumb" {
			w, _ = mw.CreateFormFile(name, name+".jpg")
		} else {
			w, _ = mw.CreateFormField(name)
		}
		w.Write(f[1])
	}
	mw.Close()
	return buf.Bytes(), mw.FormDataContentType()
}

func field(name string, b []byte) [2][]byte { return [2][]byte{[]byte(name), b} }

func (c *client) getBytes(path string) (int, http.Header, []byte) {
	c.t.Helper()
	req, _ := http.NewRequest("GET", c.base+path, nil)
	resp, err := c.http.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, resp.Header, b
}

func TestPlacePhotos(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com") // a separate space

	var r restaurantJSON
	alice.do("POST", "/api/restaurants", map[string]any{"name": "Ichiran", "visit": map[string]string{"visited_on": "2026-11-01"}}, &r)
	path := "/api/restaurants/" + itoa(r.ID)
	visit := path + "/visits/" + itoa(r.Visits[0].ID) + "/photos"
	if r.Cover != nil || r.PhotoCount != 0 || len(r.Visits[0].Photos) != 0 {
		t.Fatalf("a new place has photos: %+v", r)
	}

	// A big photo (several parts) is kept byte for byte, as it was taken.
	original, thumb := jpegOf(2*1024*1024+12345), jpegOf(20_000)
	body, ct := photoForm(field("width", []byte("4032")), field("height", []byte("3024")), field("thumb", thumb), field("photo", original))
	if code := alice.send(visit, ct, body, nil, &r); code != 201 || r.PhotoCount != 1 || len(r.Visits[0].Photos) != 1 {
		t.Fatalf("upload: %d %+v", code, r)
	}
	p := r.Visits[0].Photos[0]
	if p.Width != 4032 || p.AddedBy == "" || r.Cover == nil || *r.Cover != p.ThumbURL {
		t.Errorf("photo: %+v, cover %v", p, r.Cover)
	}
	code, h, got := alice.getBytes(p.URL)
	if code != 200 || !bytes.Equal(got, original) || h.Get("Content-Type") != "image/jpeg" || h.Get("Cache-Control") != "private, max-age=31536000, immutable" {
		t.Fatalf("original: %d %d bytes %v", code, len(got), h)
	}
	if code, _, got := alice.getBytes(p.ThumbURL); code != 200 || !bytes.Equal(got, thumb) {
		t.Errorf("thumb: %d %d bytes", code, len(got))
	}
	// Another space's photo isn't there.
	if code, _, _ := bob.getBytes(p.URL); code != 404 {
		t.Errorf("bob reads alice's photo: %d", code)
	}
	if code := bob.send(visit, ct, body, nil, nil); code != 404 {
		t.Errorf("bob adds to alice's visit: %d", code)
	}

	// The list shows the newest photo as the place's cover.
	body, ct = photoForm(field("thumb", thumb), field("photo", jpegOf(5000)))
	alice.send(visit, ct, body, nil, &r)
	var list []restaurantJSON
	alice.do("GET", "/api/restaurants", nil, &list)
	if len(list) != 1 || list[0].PhotoCount != 2 || list[0].Cover == nil || *list[0].Cover != r.Visits[0].Photos[1].ThumbURL {
		t.Errorf("list cover: %+v", list)
	}

	// The first photo can be chosen as the cover instead; not another
	// space's, nor another place's.
	if code := alice.do("PUT", path+"/cover", map[string]int64{"photo_id": p.ID}, &r); code != 200 || r.Cover == nil || *r.Cover != p.ThumbURL || r.CoverPhotoID == nil || *r.CoverPhotoID != p.ID {
		t.Fatalf("set cover: %d %+v", code, r)
	}
	alice.do("GET", "/api/restaurants", nil, &list)
	if *list[0].Cover != p.ThumbURL {
		t.Errorf("list ignores the chosen cover: %v", *list[0].Cover)
	}
	if code := bob.do("PUT", path+"/cover", map[string]int64{"photo_id": p.ID}, nil); code != 404 {
		t.Errorf("bob sets alice's cover: %d", code)
	}
	var other restaurantJSON
	alice.do("POST", "/api/restaurants", map[string]any{"name": "Afuri"}, &other)
	if code := alice.do("PUT", "/api/restaurants/"+itoa(other.ID)+"/cover", map[string]int64{"photo_id": p.ID}, nil); code != 404 {
		t.Errorf("another place's photo as cover: %d", code)
	}
	// Two photos sent together are one change in the Us tab.
	var changes []struct{ Kind string }
	alice.do("GET", "/api/activity", nil, &changes)
	n := 0
	for _, c := range changes {
		if c.Kind == "photos_added" {
			n++
		}
	}
	if n != 1 {
		t.Errorf("photos_added logged %d times: %+v", n, changes)
	}

	// What isn't a picture, or comes without a thumbnail, is refused.
	body, ct = photoForm(field("thumb", thumb), field("photo", []byte("<html><script>alert(1)</script>")))
	if code := alice.send(visit, ct, body, nil, nil); code != 415 {
		t.Errorf("html as a photo: %d, want 415", code)
	}
	body, ct = photoForm(field("photo", original))
	if code := alice.send(visit, ct, body, nil, nil); code != 400 {
		t.Errorf("photo without thumb: %d, want 400", code)
	}

	// A photo is deleted on its own, and the rest go with their visit.
	// Deleting the chosen cover goes back to the newest.
	if code := alice.do("DELETE", path+"/photos/"+itoa(p.ID), nil, &r); code != 200 || r.PhotoCount != 1 || r.Cover == nil || *r.Cover != r.Visits[0].Photos[0].ThumbURL {
		t.Fatalf("delete photo: %d %+v", code, r)
	}
	if code, _, _ := alice.getBytes(p.URL); code != 404 {
		t.Errorf("deleted photo still served: %d", code)
	}
	if code := alice.do("DELETE", path+"/visits/"+itoa(r.Visits[0].ID), nil, &r); code != 200 || r.PhotoCount != 0 || r.Cover != nil {
		t.Fatalf("delete visit: %d %+v", code, r)
	}
}
