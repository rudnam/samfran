package server_test

import (
	"io"
	"net/http"
	"regexp"
	"strings"
	"testing"

	"github.com/rudnam/samfran/internal/config"
)

func TestMapPage(t *testing.T) {
	ts := newTestServer(t, func(c *config.Config) { c.PublicOrigin = "https://samfran.example" })
	res, err := http.Get(ts.URL + "/map.html")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	csp := res.Header.Get("Content-Security-Policy")
	m := regexp.MustCompile(`'nonce-([A-Za-z0-9+/=]+)'`).FindStringSubmatch(csp)
	if res.StatusCode != 200 || m == nil {
		t.Fatalf("%d, CSP %q", res.StatusCode, csp)
	}
	// Its scripts carry the nonce; only the app may frame it; Google sees the site's origin.
	if strings.Count(string(body), `nonce="`+m[1]+`"`) != 2 {
		t.Errorf("scripts without the nonce: %s", body)
	}
	if !strings.Contains(csp, "frame-ancestors https://samfran.example") || res.Header.Get("X-Frame-Options") != "" {
		t.Errorf("framing: CSP %q, X-Frame-Options %q", csp, res.Header.Get("X-Frame-Options"))
	}
	if res.Header.Get("Referrer-Policy") != "strict-origin-when-cross-origin" {
		t.Errorf("Referrer-Policy %q", res.Header.Get("Referrer-Policy"))
	}
	// A fresh nonce each time.
	again, _ := http.Get(ts.URL + "/map.html")
	again.Body.Close()
	if again.Header.Get("Content-Security-Policy") == csp {
		t.Error("the nonce was reused")
	}

	// The app may frame it, and keeps its own strict policy.
	app, _ := http.Get(ts.URL + "/app/")
	app.Body.Close()
	if p := app.Header.Get("Content-Security-Policy"); !strings.Contains(p, "frame-src 'self'") || strings.Contains(p, "unsafe-eval") {
		t.Errorf("the app's CSP: %q", p)
	}
	// The sandboxed page loads the site's scripts cross-origin.
	js, _ := http.Get(ts.URL + "/assets/app-1.js")
	js.Body.Close()
	if js.Header.Get("Access-Control-Allow-Origin") != "*" {
		t.Errorf("assets' CORS: %q", js.Header.Get("Access-Control-Allow-Origin"))
	}
}
