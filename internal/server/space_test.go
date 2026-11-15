package server_test

import (
	"testing"

	"github.com/rudnam/samfran/internal/config"
)

type spaceJSON struct {
	Members []struct {
		Name, Email string
		Me          bool
	}
	Invites []struct{ Email string }
}

// closed is a server like production: sign-up is closed except for the
// owner's address and whoever the owner invites.
func closed(c *config.Config) {
	c.AllowSignup = false
	c.SignupEmails = []string{"fran@example.com"}
}

func TestInviteSharesTheSpace(t *testing.T) {
	ts := newTestServer(t, closed)
	fran, sam, stranger := newClient(t, ts), newClient(t, ts), newClient(t, ts)

	if code := stranger.do("POST", "/api/signup", map[string]string{"email": "x@example.com", "password": password}, nil); code != 403 {
		t.Fatalf("stranger signs up: %d, want 403", code)
	}
	if code := sam.do("POST", "/api/signup", map[string]string{"email": "sam@example.com", "password": password}, nil); code != 403 {
		t.Fatalf("sam signs up before the invitation: %d, want 403", code)
	}
	fran.signup("Fran@Example.com") // the allowed address, in any case

	var sp spaceJSON
	if code := fran.do("GET", "/api/space", nil, &sp); code != 200 || len(sp.Members) != 1 || !sp.Members[0].Me || sp.Members[0].Name != "fran" {
		t.Fatalf("new space: %d %+v", code, sp)
	}
	var added struct{ ID int64 }
	fran.do("POST", "/api/restaurants", map[string]string{"name": "Taco Loco"}, &added)

	if code := fran.do("POST", "/api/space/invites", map[string]string{"email": " Sam@Example.com "}, &sp); code != 200 || len(sp.Invites) != 1 || sp.Invites[0].Email != "sam@example.com" {
		t.Fatalf("invite: %d %+v", code, sp)
	}
	sam.signup("sam@example.com")

	// Sam is in Fran's space and sees the same restaurants.
	if sam.do("GET", "/api/space", nil, &sp); len(sp.Members) != 2 || len(sp.Invites) != 0 || sp.Members[1].Email != "sam@example.com" || !sp.Members[1].Me {
		t.Fatalf("sam's space: %+v", sp)
	}
	var list []struct{ Name string }
	if sam.do("GET", "/api/restaurants", nil, &list); len(list) != 1 || list[0].Name != "Taco Loco" {
		t.Fatalf("sam's restaurants: %+v", list)
	}

	// Names show on ratings.
	if code := sam.do("PUT", "/api/space/me", map[string]string{"name": "Sam"}, &sp); code != 200 || sp.Members[1].Name != "Sam" {
		t.Fatalf("set name: %d %+v", code, sp)
	}
	sam.do("PUT", "/api/restaurants/"+itoa(added.ID)+"/rating", map[string]int{"stars": 5}, nil)
	var r restaurantJSON
	fran.do("GET", "/api/restaurants/"+itoa(added.ID), nil, &r)
	if len(r.Ratings) != 1 || r.Ratings[0].Name != "Sam" || r.Ratings[0].Me {
		t.Errorf("fran sees sam's rating as %+v", r.Ratings)
	}

	// Someone who already has an account can't be invited into this space.
	if code := fran.do("POST", "/api/space/invites", map[string]string{"email": "sam@example.com"}, nil); code != 409 {
		t.Errorf("invite a member: %d, want 409", code)
	}
}

func TestUninvite(t *testing.T) {
	ts := newTestServer(t, closed)
	fran := newClient(t, ts)
	fran.signup("fran@example.com")
	fran.do("POST", "/api/space/invites", map[string]string{"email": "sam@example.com"}, nil)

	if code := fran.do("DELETE", "/api/space/invites/sam@example.com", nil, nil); code != 200 {
		t.Fatalf("uninvite: %d", code)
	}
	if code := fran.do("DELETE", "/api/space/invites/sam@example.com", nil, nil); code != 404 {
		t.Errorf("uninvite again: %d, want 404", code)
	}
	if code := newClient(t, ts).do("POST", "/api/signup", map[string]string{"email": "sam@example.com", "password": password}, nil); code != 403 {
		t.Errorf("sign up after the invitation was withdrawn: %d, want 403", code)
	}
}

func TestInvitationsBelongToOneSpace(t *testing.T) {
	ts := newTestServer(t) // open sign-up: two separate spaces
	a, b := newClient(t, ts), newClient(t, ts)
	a.signup("a@example.com")
	b.signup("b@example.com")
	if code := a.do("POST", "/api/space/invites", map[string]string{"email": "c@example.com"}, nil); code != 200 {
		t.Fatalf("a invites: %d", code)
	}
	if code := b.do("POST", "/api/space/invites", map[string]string{"email": "c@example.com"}, nil); code != 409 {
		t.Errorf("b invites the same address: %d, want 409", code)
	}
	if code := b.do("DELETE", "/api/space/invites/c@example.com", nil, nil); code != 404 {
		t.Errorf("b withdraws a's invitation: %d, want 404", code)
	}
	if code := a.do("POST", "/api/space/invites", map[string]string{"email": "not an email"}, nil); code != 400 {
		t.Errorf("bad address: %d, want 400", code)
	}
}

func TestDeletingAMemberKeepsTheSpace(t *testing.T) {
	ts, conn := newTestServerDB(t, closed)
	fran, sam := newClient(t, ts), newClient(t, ts)
	fran.signup("fran@example.com")
	fran.do("POST", "/api/restaurants", map[string]string{"name": "Taco Loco"}, nil)
	fran.do("POST", "/api/space/invites", map[string]string{"email": "sam@example.com"}, nil)
	sam.signup("sam@example.com")

	if code := fran.do("DELETE", "/api/me", map[string]string{"password": password}, nil); code != 204 {
		t.Fatalf("delete fran: %d", code)
	}
	var list []struct {
		Name    string `json:"name"`
		AddedBy string `json:"added_by"`
	}
	if sam.do("GET", "/api/restaurants", nil, &list); len(list) != 1 || list[0].AddedBy != "" {
		t.Fatalf("sam's restaurants after fran left: %+v", list)
	}
	// When the last member goes, so does the space and all in it.
	sam.do("DELETE", "/api/me", map[string]string{"password": password}, nil)
	var n int
	conn.QueryRow(`SELECT (SELECT count(*) FROM spaces) + (SELECT count(*) FROM restaurants)`).Scan(&n)
	if n != 0 {
		t.Errorf("%d spaces and restaurants left", n)
	}
}

func TestGoogleSignUpNeedsAnInvitation(t *testing.T) {
	ts, g := newGoogleServer(t, closed)
	fran, sam := newClient(t, ts), newClient(t, ts)
	if to := googleSignIn(t, sam, g, "sub-sam", "sam@gmail.com"); to != "/app/?signin_error=closed" {
		t.Fatalf("uninvited google sign-up landed on %q", to)
	}
	fran.signup("fran@example.com")
	fran.do("POST", "/api/space/invites", map[string]string{"email": "sam@gmail.com"}, nil)
	if to := googleSignIn(t, sam, g, "sub-sam", "sam@gmail.com"); to != "/app/" {
		t.Fatalf("invited google sign-up landed on %q", to)
	}
	var sp spaceJSON
	if sam.do("GET", "/api/space", nil, &sp); len(sp.Members) != 2 {
		t.Errorf("sam's space after google sign-up: %+v", sp)
	}
}
