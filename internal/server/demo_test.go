package server_test

import (
	"testing"

	"github.com/rudnam/samfran/internal/config"
)

func withDemo(c *config.Config) {
	c.Demo = true
}

type meJSON struct {
	Email string `json:"email"`
	Demo  bool   `json:"demo"`
}

func startDemo(t *testing.T, c *client, lang string) meJSON {
	t.Helper()
	c.headers["Accept-Language"] = lang
	var me meJSON
	if code := c.do("POST", "/api/demo", map[string]string{"tz": "Asia/Tokyo"}, &me); code != 201 {
		t.Fatalf("POST /api/demo: %d", code)
	}
	if !me.Demo {
		t.Fatalf("not a demo: %+v", me)
	}
	return me
}

// restaurantsOf lists the names of the signed-in person's restaurants, in the order added.
func restaurantsOf(t *testing.T, c *client) []string {
	t.Helper()
	var list []struct{ Name string }
	if code := c.do("GET", "/api/restaurants", nil, &list); code != 200 {
		t.Fatalf("GET /api/restaurants: %d", code)
	}
	names := make([]string, len(list))
	for i, r := range list {
		names[i] = r.Name
	}
	return names
}

func TestDemoInJapanese(t *testing.T) {
	ts := newTestServer(t, withDemo)
	c := newClient(t, ts)
	var opts struct{ Demo bool }
	if c.do("GET", "/api/auth/options", nil, &opts); !opts.Demo {
		t.Fatal("options don't offer the demo")
	}
	startDemo(t, c, "ja")
	names := restaurantsOf(t, c)
	if len(names) < 3 || names[0] != "はなび亭" {
		t.Errorf("restaurants %q", names)
	}
}

func TestDemoInEnglish(t *testing.T) {
	ts := newTestServer(t, withDemo)
	c := newClient(t, ts)
	startDemo(t, c, "en-US,en;q=0.9")
	names := restaurantsOf(t, c)
	if len(names) < 3 || names[0] != "Hanabi Grill" {
		t.Errorf("restaurants %q", names)
	}
}

func TestDemoIsDeleted(t *testing.T) {
	ts, conn := newTestServerDB(t, withDemo)
	demos := func() (n int) {
		t.Helper()
		if err := conn.QueryRow(`SELECT count(*) FROM users WHERE demo_expires_at IS NOT NULL`).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}

	// Ending a demo deletes it.
	c := newClient(t, ts)
	startDemo(t, c, "en")
	if c.do("POST", "/api/logout", nil, nil); demos() != 0 {
		t.Errorf("%d demos left after signing out", demos())
	}

	// One whose day is up goes when the next demo starts.
	startDemo(t, newClient(t, ts), "en")
	if _, err := conn.Exec(`UPDATE users SET demo_expires_at = now() - interval '1 minute'`); err != nil {
		t.Fatal(err)
	}
	startDemo(t, newClient(t, ts), "en")
	if n := demos(); n != 1 {
		t.Errorf("%d demos; the expired one should be gone", n)
	}
	var orphans int
	conn.QueryRow(`SELECT count(*) FROM restaurants WHERE space_id NOT IN (SELECT space_id FROM space_members)`).Scan(&orphans)
	if orphans != 0 {
		t.Errorf("%d records outlived their demo", orphans)
	}
}

func TestDemoCanBeOff(t *testing.T) {
	ts := newTestServer(t) // Demo false
	c := newClient(t, ts)
	var opts struct{ Demo bool }
	if c.do("GET", "/api/auth/options", nil, &opts); opts.Demo {
		t.Error("options offer a demo that is off")
	}
	if code := c.do("POST", "/api/demo", map[string]string{}, nil); code != 404 {
		t.Errorf("POST /api/demo when off: %d, want 404", code)
	}
	// Nobody can sign up with a demo's made-up address.
	if code := c.do("POST", "/api/signup", map[string]string{"email": "demo-1@demo.invalid", "password": password}, nil); code != 400 {
		t.Errorf("sign-up as a demo address: %d, want 400", code)
	}
}
