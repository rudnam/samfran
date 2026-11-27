package server_test

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/rudnam/samfran/internal/config"
)

func TestAndroidDownload(t *testing.T) {
	// None configured: nothing offered.
	ts := newTestServer(t)
	if _, body := get(t, ts.URL+"/api/downloads"); body != `{"android":null}`+"\n" {
		t.Errorf("downloads without an APK: %s", body)
	}
	if resp, _ := get(t, ts.URL+"/download/samfran.apk"); resp.StatusCode != 404 {
		t.Errorf("APK without one: %d", resp.StatusCode)
	}

	// Configured, but not built yet.
	file := filepath.Join(t.TempDir(), "samfran.apk")
	ts = newTestServer(t, func(c *config.Config) { c.AndroidAPK = file })
	if _, body := get(t, ts.URL+"/api/downloads"); body != `{"android":null}`+"\n" {
		t.Errorf("downloads before the APK exists: %s", body)
	}

	// Dropped in while the server runs.
	if err := os.WriteFile(file, []byte("PK fake apk"), 0o644); err != nil {
		t.Fatal(err)
	}
	var got struct {
		Android *struct {
			URL  string `json:"url"`
			Size int64  `json:"size"`
		} `json:"android"`
	}
	_, body := get(t, ts.URL+"/api/downloads")
	if err := json.Unmarshal([]byte(body), &got); err != nil || got.Android == nil ||
		got.Android.URL != "/download/samfran.apk" || got.Android.Size != 11 {
		t.Fatalf("downloads: %s", body)
	}
	resp, body := get(t, ts.URL+got.Android.URL)
	if resp.StatusCode != 200 || body != "PK fake apk" ||
		resp.Header.Get("Content-Type") != "application/vnd.android.package-archive" ||
		!strings.Contains(resp.Header.Get("Content-Disposition"), "samfran.apk") {
		t.Errorf("APK: %d %q %v", resp.StatusCode, body, resp.Header)
	}
	// Not a real APK, so no version to offer the app.
	if _, body := get(t, ts.URL+"/api/downloads"); strings.Contains(body, "version") {
		t.Errorf("version of a fake APK: %s", body)
	}

	// A real build replaces it: the app learns its version from here.
	manifest, err := os.ReadFile("testdata/AndroidManifest.xml") // from a debug build of android/: versionCode 4, versionName 2.1
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	if w, err := zw.Create("AndroidManifest.xml"); err != nil {
		t.Fatal(err)
	} else if _, err := w.Write(manifest); err != nil {
		t.Fatal(err)
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(file, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	var versioned struct {
		Android struct {
			Version     int64  `json:"version"`
			VersionName string `json:"version_name"`
			Changelog   []any  `json:"changelog"`
		} `json:"android"`
	}
	_, body = get(t, ts.URL+"/api/downloads")
	if err := json.Unmarshal([]byte(body), &versioned); err != nil ||
		versioned.Android.Version != 4 || versioned.Android.VersionName != "2.1" ||
		len(versioned.Android.Changelog) != 0 {
		t.Errorf("downloads with a real APK: %s", body)
	}

	// An APK with assets/changelog.json returns entries newer than ?since=.
	writeAPK := func(changelogJSON string) {
		var b bytes.Buffer
		w := zip.NewWriter(&b)
		if f, err := w.Create("AndroidManifest.xml"); err != nil {
			t.Fatal(err)
		} else if _, err := f.Write(manifest); err != nil {
			t.Fatal(err)
		}
		if f, err := w.Create("assets/changelog.json"); err != nil {
			t.Fatal(err)
		} else if _, err := f.Write([]byte(changelogJSON)); err != nil {
			t.Fatal(err)
		}
		if err := w.Close(); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(file, b.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	writeAPK(`[
		{"version":4,"version_name":"2.1","en":["Show what's new"],"ja":["変更内容を表示"]},
		{"version":3,"version_name":"2.0.3","en":["Faster ride alarm"]},
		{"version":2,"version_name":"2.0.2","en":["Older fix"]}
	]`)
	type clResp struct {
		Android struct {
			Version   int64 `json:"version"`
			Changelog []struct {
				Version     int64    `json:"version"`
				VersionName string   `json:"version_name"`
				En          []string `json:"en"`
				Ja          []string `json:"ja"`
			} `json:"changelog"`
		} `json:"android"`
	}
	var all, since2, since4 clResp
	_, body = get(t, ts.URL+"/api/downloads")
	if err := json.Unmarshal([]byte(body), &all); err != nil || len(all.Android.Changelog) != 3 {
		t.Fatalf("all changelog entries: %s", body)
	}
	_, body = get(t, ts.URL+"/api/downloads?since=2")
	if err := json.Unmarshal([]byte(body), &since2); err != nil ||
		len(since2.Android.Changelog) != 2 ||
		since2.Android.Changelog[0].Version != 4 ||
		since2.Android.Changelog[1].Version != 3 ||
		since2.Android.Changelog[0].Ja[0] != "変更内容を表示" {
		t.Fatalf("since=2 changelog entries: %s", body)
	}
	_, body = get(t, ts.URL+"/api/downloads?since=4")
	if err := json.Unmarshal([]byte(body), &since4); err != nil || len(since4.Android.Changelog) != 0 ||
		strings.Contains(body, `"changelog"`) {
		t.Fatalf("since=4 should omit changelog: %s", body)
	}

	// Malformed changelog.json doesn't break version detection.
	writeAPK(`{not json`)
	var broken clResp
	_, body = get(t, ts.URL+"/api/downloads")
	if err := json.Unmarshal([]byte(body), &broken); err != nil ||
		broken.Android.Version != 4 || len(broken.Android.Changelog) != 0 {
		t.Fatalf("broken changelog still returns version: %s", body)
	}
}
