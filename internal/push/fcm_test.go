package push

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func serviceAccountJSON(t *testing.T, key *rsa.PrivateKey, tokenURL string) string {
	t.Helper()
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(map[string]string{
		"type":         "service_account",
		"project_id":   "samfran-test",
		"client_email": "push@samfran-test.iam.gserviceaccount.com",
		"private_key":  string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})),
		"token_uri":    tokenURL,
	})
	return string(b)
}

func TestFCMSignsInAndSends(t *testing.T) {
	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	var signIns, sends atomic.Int32
	var got map[string]any
	google := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/token":
			signIns.Add(1)
			r.ParseForm()
			if r.Form.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" {
				t.Errorf("grant_type %q", r.Form.Get("grant_type"))
			}
			parts := strings.Split(r.Form.Get("assertion"), ".")
			if len(parts) != 3 {
				t.Fatalf("assertion %q", r.Form.Get("assertion"))
			}
			sig, _ := base64.RawURLEncoding.DecodeString(parts[2])
			sum := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
			if err := rsa.VerifyPKCS1v15(&key.PublicKey, crypto.SHA256, sum[:], sig); err != nil {
				t.Errorf("signature: %v", err)
			}
			claims, _ := base64.RawURLEncoding.DecodeString(parts[1])
			if !strings.Contains(string(claims), "firebase.messaging") || !strings.Contains(string(claims), "push@samfran-test") {
				t.Errorf("claims %s", claims)
			}
			w.Write([]byte(`{"access_token":"ya29.test","expires_in":3600}`))
		case "/v1/projects/samfran-test/messages:send":
			sends.Add(1)
			if r.Header.Get("Authorization") != "Bearer ya29.test" {
				t.Errorf("authorization %q", r.Header.Get("Authorization"))
			}
			var body map[string]any
			json.NewDecoder(r.Body).Decode(&body)
			msg := body["message"].(map[string]any)
			if msg["token"] == "gone" {
				w.WriteHeader(404)
				w.Write([]byte(`{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}`))
				return
			}
			got = msg
			w.Write([]byte(`{"name":"projects/samfran-test/messages/1"}`))
		default:
			t.Errorf("unexpected %s", r.URL.Path)
		}
	}))
	defer google.Close()

	// The base64 form, as it goes in an .env file.
	f, err := NewFCM(base64.StdEncoding.EncodeToString([]byte(serviceAccountJSON(t, key, google.URL+"/token"))))
	if err != nil {
		t.Fatal(err)
	}
	f.SendURL = google.URL + "/v1/projects/%s/messages:send"
	ctx := context.Background()
	if err := f.Send(ctx, "phone-1", map[string]string{"type": "lock_request", "request_id": "7"}); err != nil {
		t.Fatal(err)
	}
	if err := f.Send(ctx, "phone-1", map[string]string{"type": "lock_request"}); err != nil {
		t.Fatal(err)
	}
	if signIns.Load() != 1 || sends.Load() != 2 {
		t.Errorf("sign-ins %d, sends %d; want 1 and 2", signIns.Load(), sends.Load())
	}
	if got["data"].(map[string]any)["type"] != "lock_request" || got["android"].(map[string]any)["priority"] != "high" {
		t.Errorf("message %v", got)
	}
	if err := f.Send(ctx, "gone", nil); !errors.Is(err, ErrUnregistered) {
		t.Errorf("unregistered token: %v", err)
	}
}

func TestNewFCMRefusesWhatIsntAKey(t *testing.T) {
	for _, in := range []string{"not base64!", `{"project_id":"x"}`, base64.StdEncoding.EncodeToString([]byte("{}"))} {
		if _, err := NewFCM(in); err == nil {
			t.Errorf("NewFCM(%q) took it", in)
		}
	}
}
