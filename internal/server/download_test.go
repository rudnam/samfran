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
		} `json:"android"`
	}
	_, body = get(t, ts.URL+"/api/downloads")
	if err := json.Unmarshal([]byte(body), &versioned); err != nil ||
		versioned.Android.Version != 4 || versioned.Android.VersionName != "2.1" {
		t.Errorf("downloads with a real APK: %s", body)
	}
}
