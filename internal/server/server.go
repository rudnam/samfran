// Package server assembles the HTTP handler: it wires each feature's routes
// and wraps them in the shared middleware. A new feature gets one line here.
package server

import (
	"context"
	"database/sql"
	"io/fs"
	"mime"
	"net/http"
	"path"
	"slices"
	"strings"
	"time"

	"github.com/rudnam/samfran/internal/account"
	"github.com/rudnam/samfran/internal/activity"
	"github.com/rudnam/samfran/internal/applock"
	"github.com/rudnam/samfran/internal/bus"
	"github.com/rudnam/samfran/internal/config"
	"github.com/rudnam/samfran/internal/demo"
	"github.com/rudnam/samfran/internal/httpx"
	"github.com/rudnam/samfran/internal/mailer"
	"github.com/rudnam/samfran/internal/mcp"
	"github.com/rudnam/samfran/internal/push"
	"github.com/rudnam/samfran/internal/restaurant"
	"github.com/rudnam/samfran/internal/shift"
	"github.com/rudnam/samfran/internal/space"
)

// appName is what emails call the app.
const appName = "SamFran"

func init() {
	// The runtime image has no /etc/mime.types, and Go doesn't know these.
	mime.AddExtensionType(".webmanifest", "application/manifest+json")
	mime.AddExtensionType(".woff2", "font/woff2")
}

// New returns the app's complete HTTP handler. static is the built web app;
// version is reported by /healthz so you can tell which build is running.
func New(cfg config.Config, conn *sql.DB, static fs.FS, version string) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /healthz", health(conn, version))
	mux.Handle("GET /api/downloads", downloads(cfg.AndroidAPK))
	mux.Handle("GET "+apkPath, apk(cfg.AndroidAPK))
	if cfg.AndroidPackage != "" {
		mux.Handle("GET /.well-known/assetlinks.json", assetLinks(cfg.AndroidPackage, cfg.AndroidCertFingerprints))
	}

	// Features
	var google *account.GoogleConfig
	if cfg.GoogleClientID != "" {
		google = &account.GoogleConfig{
			ClientID:     cfg.GoogleClientID,
			ClientSecret: cfg.GoogleClientSecret,
			RedirectURL:  cfg.PublicOrigin + "/api/auth/google/callback",
			AuthURL:      cfg.GoogleAuthURL,
			TokenURL:     cfg.GoogleTokenURL,
		}
	}
	sender := cfg.Mailer
	if sender == nil && cfg.SMTPHost != "" {
		sender = &mailer.SMTP{Host: cfg.SMTPHost, Port: cfg.SMTPPort, Username: cfg.SMTPUsername,
			Password: cfg.SMTPPassword, From: cfg.MailFrom}
	}
	spaces := space.NewStore(conn)
	restaurants := restaurant.NewStore(conn)
	var demoFiller account.DemoFiller
	if cfg.Demo {
		demoFiller = demo.Filler(spaces, restaurants)
	}
	// With sign-up closed, the listed emails and anyone invited may still join.
	signupAllowed := func(ctx context.Context, email string) (bool, error) {
		if slices.Contains(cfg.SignupEmails, email) || spaces.SignupAllowedByLink(ctx) {
			return true, nil
		}
		return spaces.Invited(ctx, email)
	}
	accounts := account.NewHandler(account.Config{
		AppName:       appName,
		SecureCookies: cfg.SecureCookies,
		TrustProxy:    cfg.TrustProxy,
		AllowSignup:   cfg.AllowSignup,
		SignupAllowed: signupAllowed,
		Created: func(ctx context.Context, userID int64) error {
			// Joins the space of the invite link the request carries, else
			// the one its email was invited to, else a new one.
			if joined, err := spaces.JoinByLink(ctx, userID); joined || err != nil {
				return err
			}
			_, err := spaces.Of(ctx, userID)
			return err
		},
		AttemptsPerMinute: cfg.AuthRateLimit,
		Google:            google,
		Mail:              sender,
		PublicOrigin:      cfg.PublicOrigin,
		AppScheme:         cfg.AndroidPackage,
		Demo:              demoFiller,
	}, account.NewStore(conn))
	accounts.Register(mux)
	activities := activity.NewStore(conn)
	spaceHandler := space.NewHandler(spaces, accounts.RequireUser)
	spaceHandler.Log = activities.Log
	spaceHandler.SecureCookies = cfg.SecureCookies
	spaceHandler.Register(mux)
	restaurantHandler := restaurant.NewHandler(restaurants)
	restaurantHandler.Log = activities.Log
	restaurantHandler.UseGoogle(cfg.GooglePlacesAPIKey, cfg.GooglePlacesURL)
	restaurantHandler.Locate = cfg.LocatePlaces
	restaurantHandler.MapKey = cfg.GoogleMapsBrowserKey
	restaurantHandler.Register(mux, spaceHandler.Require)
	reader := cfg.ShiftReader
	if reader == nil && cfg.AnthropicAPIKey != "" {
		reader = shift.NewClaude(cfg.AnthropicAPIKey, "")
	}
	shifts := shift.NewStore(conn)
	shiftHandler := shift.NewHandler(shifts, reader)
	shiftHandler.Log = activities.Log
	shiftHandler.Register(mux, spaceHandler.Require)
	activity.NewHandler(activities).Register(mux, spaceHandler.Require, func(r *http.Request) int64 { return account.UserFrom(r).ID })
	busHandler := bus.NewHandler(conn)
	busHandler.Log = activities.Log
	busHandler.SpaceOf = spaces.Of
	busHandler.Register(mux, accounts.RequireUser)
	mcpHandler := mcp.NewHandler(mcp.NewStore(conn), spaces, restaurants, shifts,
		mcp.Config{Version: version, PublicOrigin: cfg.PublicOrigin, TrustProxy: cfg.TrustProxy})
	mcpHandler.Log = activities.Log
	mcpHandler.Register(mux, accounts.RequireUser)
	pushSender := cfg.Push
	if pushSender == nil && cfg.FirebaseServiceAccount != "" {
		if fcm, err := push.NewFCM(cfg.FirebaseServiceAccount); err == nil { // config.Load checked it
			pushSender = fcm
		}
	}
	notifier := push.NewNotifier(conn, pushSender)
	activities.Notify = notifier.Notify
	push.NewHandler(conn).Register(mux, accounts.RequireUser)
	lockHandler := applock.NewHandler(applock.NewStore(conn))
	lockHandler.Notify = notifier.Notify
	lockHandler.Register(mux, spaceHandler.Require)

	// Unknown API paths get a JSON 404 rather than the web page.
	mux.Handle("/api/", httpx.HandlerFunc(func(http.ResponseWriter, *http.Request) error {
		return httpx.Errorf(http.StatusNotFound, "not found")
	}))
	mux.Handle("GET /map.html", mapPage(static, cfg.PublicOrigin))
	mux.Handle("/", webApp(static))

	return httpx.Chain(space.WithInvite(mux),
		httpx.LogRequests,
		httpx.Recover,
		httpx.SecurityHeaders,
		httpx.AllowApp,
		httpx.CheckOrigin(cfg.PublicOrigin),
	)
}

// health answers 200 when the database is reachable. Docker, load balancers
// and uptime checks all use it.
func health(conn *sql.DB, version string) http.Handler {
	return httpx.HandlerFunc(func(w http.ResponseWriter, r *http.Request) error {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := conn.PingContext(ctx); err != nil {
			return httpx.Errorf(http.StatusServiceUnavailable, "database unreachable")
		}
		httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok", "version": version})
		return nil
	})
}

// webApp serves the built web app. The introduction page (landing.html) is
// at /, and the single-page app (index.html) at /app/: paths under /app/ that
// are not files get index.html, so client-side routes survive a reload. Vite
// puts content-hashed files in assets/, which—along with fonts/—can be cached
// for good; everything else is revalidated, so a deploy shows up on the next load.
func webApp(static fs.FS) http.Handler {
	files := http.FileServerFS(static)
	index, err := fs.ReadFile(static, "index.html")
	if err != nil {
		index = []byte("<!doctype html><title>Missing build</title>")
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			httpx.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}
		name := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if strings.HasPrefix(name, "assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
			// The map page runs in a sandboxed frame, with no origin of its
			// own, so its scripts load cross-origin (mappage.go). They're the
			// same public files for anyone.
			w.Header().Set("Access-Control-Allow-Origin", "*")
		} else if strings.HasPrefix(name, "fonts/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		switch {
		case name == "" || name == "landing.html":
			http.ServeFileFS(w, r, static, "landing.html")
		case name == "privacy" || name == "privacy.html":
			http.ServeFileFS(w, r, static, "privacy.html")
		case r.URL.Path == "/app":
			to := "/app/"
			if r.URL.RawQuery != "" {
				to += "?" + r.URL.RawQuery
			}
			http.Redirect(w, r, to, http.StatusMovedPermanently)
		case name == "app" || strings.HasPrefix(name, "app/") || name == "index.html":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Write(index)
		case path.Ext(name) == "":
			// Not a file, and no page of ours: no directory listings.
			http.NotFound(w, r)
		default:
			files.ServeHTTP(w, r)
		}
	})
}

// assetLinks proves to Android that the app with this package and signing
// key belongs to this site, so it can show the site without browser UI.
func assetLinks(pkg string, fingerprints []string) http.Handler {
	body := []map[string]any{{
		"relation": []string{"delegate_permission/common.handle_all_urls"},
		"target": map[string]any{
			"namespace":                "android_app",
			"package_name":             pkg,
			"sha256_cert_fingerprints": fingerprints,
		},
	}}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		httpx.WriteJSON(w, http.StatusOK, body)
	})
}
