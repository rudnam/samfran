package server_test

import (
	"net/http"
	"strings"
	"testing"
)

// openLink visits an invite link the way a browser does, keeping its cookie,
// and returns where it sent the browser.
func openLink(t *testing.T, c *client, path string) string {
	t.Helper()
	c.http.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	defer func() { c.http.CheckRedirect = nil }()
	resp, err := c.http.Get(c.base + path)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.Header.Get("Location")
}

func TestInviteLink(t *testing.T) {
	ts := newTestServer(t, closed)
	fran, sam, stranger := newClient(t, ts), newClient(t, ts), newClient(t, ts)
	fran.signup("fran@example.com")
	fran.do("PUT", "/api/space/me", map[string]string{"name": "Fran"}, nil)
	fran.do("POST", "/api/restaurants", map[string]string{"name": "Taco Loco"}, nil)

	var link struct{ Path string }
	if code := fran.do("POST", "/api/space/invite-link", nil, &link); code != 201 || !strings.HasPrefix(link.Path, "/join/") {
		t.Fatalf("create: %d %+v", code, link)
	}
	var sp struct {
		InviteLink *struct{ ExpiresAt string } `json:"invite_link"`
	}
	if fran.do("GET", "/api/space", nil, &sp); sp.InviteLink == nil {
		t.Error("the open link isn't shown")
	}

	// Without the link, sign-up is closed.
	if code := sam.do("POST", "/api/signup", map[string]string{"email": "samantha@example.net", "password": password}, nil); code != 403 {
		t.Fatalf("sign up without the link: %d", code)
	}
	// Opening it leads to the join page, named after who sent it.
	if to := openLink(t, sam, link.Path); to != "/app/#/join?from=Fran" {
		t.Fatalf("join: %q", to)
	}
	sam.signup("samantha@example.net") // any email
	var list []struct{ Name string }
	if sam.do("GET", "/api/restaurants", nil, &list); len(list) != 1 || list[0].Name != "Taco Loco" {
		t.Fatalf("sam isn't in fran's space: %+v", list)
	}
	if fran.do("GET", "/api/space", nil, &sp); sp.InviteLink != nil {
		t.Error("a used link is still open")
	}

	// It works once.
	if to := openLink(t, stranger, link.Path); to != "/app/#/join?invalid=1" {
		t.Errorf("used link: %q", to)
	}
	if code := stranger.do("POST", "/api/signup", map[string]string{"email": "x@example.com", "password": password}, nil); code != 403 {
		t.Errorf("sign up with a used link: %d", code)
	}

	// A cancelled link stops working.
	fran.do("POST", "/api/space/invite-link", nil, &link)
	fran.do("DELETE", "/api/space/invite-link", nil, nil)
	if to := openLink(t, newClient(t, ts), link.Path); to != "/app/#/join?invalid=1" {
		t.Errorf("cancelled link: %q", to)
	}
}
