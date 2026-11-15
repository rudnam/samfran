// Command seed fills an account with sample data through the API, so a
// preview has something on its screens. preview.sh runs it against its fresh
// database:
//
//	go run ./e2e/seed -url http://server:8080 -email preview@example.com -password preview-password
//
// It goes through the API, as a user would, so it needs no access to the
// database and keeps working as the schema changes. When you replace the
// example feature, replace what this adds too.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"net/http/cookiejar"
	"strconv"
	"time"
)

type place struct {
	name, area, cuisine, notes string
	daysAgo                    []int // visits; none puts it on the want-to-go list
	mine, theirs               int   // ratings
}

var places = []place{
	{"Hanabi Grill", "Station East", "Hamburg steak", "Book ahead on weekends: the wait was two hours.", []int{3, 40}, 5, 5},
	{"Gyoza Komachi", "Station East", "Gyoza", "", []int{12}, 5, 4},
	{"Ramen Tsubaki", "Old Town", "Ramen", "", []int{20}, 4, 3},
	{"Unagi Mizuno", "Lakeside", "Eel", "For a special occasion.", []int{75}, 4, 5},
	{"Blue Sky Café", "Riverside", "Café", "Their pancakes are supposed to be great.", nil, 0, 0},
	{"Taco Loco", "Station East", "Mexican", "", nil, 0, 0},
}

func main() {
	base := flag.String("url", "http://localhost:8080", "the app's address")
	email := flag.String("email", "preview@example.com", "account to fill")
	password := flag.String("password", "preview-password", "its password")
	flag.Parse()

	me := newClient(*base)
	me.must("POST", "/api/login", map[string]string{"email": *email, "password": *password}, nil)
	me.must("PUT", "/api/space/me", map[string]string{"name": "Fran"}, nil)

	// A partner, invited and signed up, so ratings show both of you.
	partner := newClient(*base)
	partnerEmail := "sam-" + strconv.FormatInt(time.Now().Unix(), 36) + "@example.com"
	me.must("POST", "/api/space/invites", map[string]string{"email": partnerEmail}, nil)
	partner.must("POST", "/api/signup", map[string]string{"email": partnerEmail, "password": "partner-password"}, nil)
	partner.must("PUT", "/api/space/me", map[string]string{"name": "Sam"}, nil)

	for _, p := range places {
		var r struct{ ID int64 }
		me.must("POST", "/api/restaurants", map[string]string{"name": p.name, "area": p.area, "cuisine": p.cuisine, "notes": p.notes}, &r)
		path := "/api/restaurants/" + strconv.FormatInt(r.ID, 10)
		for i, d := range p.daysAgo {
			who := me
			if i%2 == 1 {
				who = partner
			}
			who.must("POST", path+"/visits", map[string]string{"visited_on": time.Now().AddDate(0, 0, -d).Format(time.DateOnly)}, nil)
		}
		if p.mine > 0 {
			me.must("PUT", path+"/rating", map[string]int{"stars": p.mine}, nil)
		}
		if p.theirs > 0 {
			partner.must("PUT", path+"/rating", map[string]int{"stars": p.theirs}, nil)
		}
	}
	fmt.Printf("Added %d sample places for %s, shared with %s\n", len(places), *email, partnerEmail)
}

func newClient(base string) *client {
	jar, _ := cookiejar.New(nil)
	return &client{base: base, http: &http.Client{Jar: jar, Timeout: 30 * time.Second}}
}

type client struct {
	base string
	http *http.Client
}

// must sends body as JSON and stops the program on anything but a 2xx. The
// Origin header passes the app's cross-site check, as a browser's would.
func (c *client) must(method, path string, body, out any) {
	b, err := json.Marshal(body)
	if err != nil {
		log.Fatal(err)
	}
	req, err := http.NewRequest(method, c.base+path, bytes.NewReader(b))
	if err != nil {
		log.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", c.base)
	res, err := c.http.Do(req)
	if err != nil {
		log.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode/100 != 2 {
		var e struct{ Error string }
		_ = json.NewDecoder(res.Body).Decode(&e)
		log.Fatalf("%s %s: %s %s", method, path, res.Status, e.Error)
	}
	if out != nil {
		_ = json.NewDecoder(res.Body).Decode(out)
	}
}
