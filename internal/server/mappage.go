package server

// The places' map (web/map.html, web/src/map/main.ts) loads Google Maps,
// whose script needs what the app's Content-Security-Policy doesn't allow:
// scripts and styles from Google's hosts, eval, and inline styles. So the
// map is its own page with its own policy, Google's strict one (a fresh
// nonce on each answer, 'strict-dynamic' for the scripts Google's loader
// adds), and the app shows it in a sandboxed frame without
// allow-same-origin: whatever runs there can't reach the app, its storage
// or its session. The app sends it the places to show (postMessage).

import (
	"crypto/rand"
	"encoding/base64"
	"io/fs"
	"net/http"
	"strings"
)

func mapPage(static fs.FS, origin string) http.Handler {
	page, err := fs.ReadFile(static, "map.html")
	if err != nil {
		page = []byte("<!doctype html><title>Missing build</title>")
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b := make([]byte, 18)
		rand.Read(b)
		nonce := base64.StdEncoding.EncodeToString(b)
		// Only the app may frame it. The sandboxed page has no origin of its
		// own, so 'self' wouldn't match the app: name the site.
		self := origin
		if self == "" {
			scheme := "http"
			if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
				scheme = "https"
			}
			self = scheme + "://" + r.Host
		}
		h := w.Header()
		h.Set("Content-Security-Policy", strings.Join([]string{
			"default-src 'none'",
			"script-src 'nonce-" + nonce + "' 'strict-dynamic' https: 'unsafe-eval' blob:",
			"style-src 'unsafe-inline' https://fonts.googleapis.com",
			"img-src https://*.googleapis.com https://*.gstatic.com https://*.google.com https://*.googleusercontent.com data:",
			"font-src https://fonts.gstatic.com",
			"connect-src https://*.googleapis.com https://*.google.com https://*.gstatic.com data: blob:",
			"frame-src https://*.google.com",
			"worker-src blob:",
			"object-src 'none'",
			"base-uri 'none'",
			"form-action 'none'",
			"frame-ancestors " + self,
		}, "; "))
		h.Del("X-Frame-Options") // frame-ancestors says who may frame it
		// Google checks the key's allowed sites against the address this
		// page sends: the site's origin, nothing more.
		h.Set("Referrer-Policy", "strict-origin-when-cross-origin")
		h.Set("Cache-Control", "no-store")
		h.Set("Content-Type", "text/html; charset=utf-8")
		html := strings.ReplaceAll(string(page), "<script ", `<script nonce="`+nonce+`" `)
		html = strings.ReplaceAll(html, `<link rel="modulepreload" `, `<link rel="modulepreload" nonce="`+nonce+`" `)
		w.Write([]byte(html))
	})
}
