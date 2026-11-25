package push

import (
	"bytes"
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
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// FCM sends through Firebase Cloud Messaging's HTTP v1 API, signed in as a
// service account of the Firebase project: it signs a JWT with the
// account's key and trades it at Google's token endpoint for an hour's
// access token (OAuth 2.0 for service accounts), with the standard library
// only. Messages carry data only, at high priority, so the app shows its
// own notification (with its buttons) even when it's closed.
type FCM struct {
	projectID   string
	clientEmail string
	key         *rsa.PrivateKey
	tokenURL    string
	// SendURL is FCM's address, with %s for the project; tests point it elsewhere.
	SendURL string
	Client  *http.Client

	mu      sync.Mutex
	access  string
	expires time.Time
}

// serviceAccount is the part of a service account's JSON key that's used.
type serviceAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
}

// NewFCM reads a service account's JSON key (FIREBASE_SERVICE_ACCOUNT): the
// JSON itself, or the same in base64, which fits on one line of an .env file.
func NewFCM(key string) (*FCM, error) {
	key = strings.TrimSpace(key)
	raw := []byte(key)
	if !strings.HasPrefix(key, "{") {
		var err error
		if raw, err = base64.StdEncoding.DecodeString(key); err != nil {
			return nil, errors.New("FIREBASE_SERVICE_ACCOUNT must be a service account's JSON key, or that JSON in base64")
		}
	}
	var sa serviceAccount
	if err := json.Unmarshal(raw, &sa); err != nil || sa.ProjectID == "" || sa.ClientEmail == "" || sa.PrivateKey == "" {
		return nil, errors.New("FIREBASE_SERVICE_ACCOUNT must be a service account's JSON key, with project_id, client_email and private_key")
	}
	block, _ := pem.Decode([]byte(sa.PrivateKey))
	if block == nil {
		return nil, errors.New("FIREBASE_SERVICE_ACCOUNT: private_key isn't a PEM key")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("FIREBASE_SERVICE_ACCOUNT: private_key: %w", err)
	}
	rsaKey, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("FIREBASE_SERVICE_ACCOUNT: private_key isn't an RSA key")
	}
	if sa.TokenURI == "" {
		sa.TokenURI = "https://oauth2.googleapis.com/token"
	}
	return &FCM{
		projectID:   sa.ProjectID,
		clientEmail: sa.ClientEmail,
		key:         rsaKey,
		tokenURL:    sa.TokenURI,
		SendURL:     "https://fcm.googleapis.com/v1/projects/%s/messages:send",
		Client:      &http.Client{Timeout: 15 * time.Second},
	}, nil
}

func (f *FCM) Send(ctx context.Context, token string, data map[string]string) error {
	access, err := f.accessToken(ctx)
	if err != nil {
		return err
	}
	body, _ := json.Marshal(map[string]any{"message": map[string]any{
		"token": token,
		"data":  data,
		// Asking can't wait; after 15 minutes it has expired anyway.
		"android": map[string]any{"priority": "high", "ttl": "900s"},
	}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, fmt.Sprintf(f.SendURL, f.projectID), bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+access)
	req.Header.Set("Content-Type", "application/json")
	resp, err := f.Client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	msg, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode == http.StatusNotFound || bytes.Contains(msg, []byte("UNREGISTERED")) {
		return ErrUnregistered
	}
	if resp.StatusCode == http.StatusUnauthorized {
		f.mu.Lock()
		f.access = "" // the next message signs in again
		f.mu.Unlock()
	}
	return fmt.Errorf("fcm: %d %s", resp.StatusCode, bytes.TrimSpace(msg))
}

// accessToken is the hour's access token, made again five minutes early.
func (f *FCM) accessToken(ctx context.Context) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.access != "" && time.Now().Before(f.expires.Add(-5*time.Minute)) {
		return f.access, nil
	}
	assertion, err := f.jwt(time.Now())
	if err != nil {
		return "", err
	}
	form := url.Values{"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {assertion}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := f.Client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return "", fmt.Errorf("fcm sign-in: %d %s", resp.StatusCode, bytes.TrimSpace(msg))
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || out.AccessToken == "" {
		return "", fmt.Errorf("fcm sign-in: unreadable answer: %v", err)
	}
	f.access = out.AccessToken
	f.expires = time.Now().Add(time.Duration(out.ExpiresIn) * time.Second)
	return f.access, nil
}

// jwt is the service account's signed claim to FCM's scope.
func (f *FCM) jwt(now time.Time) (string, error) {
	enc := base64.RawURLEncoding
	header, _ := json.Marshal(map[string]string{"alg": "RS256", "typ": "JWT"})
	claims, _ := json.Marshal(map[string]any{
		"iss":   f.clientEmail,
		"scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud":   f.tokenURL,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})
	signing := enc.EncodeToString(header) + "." + enc.EncodeToString(claims)
	sum := sha256.Sum256([]byte(signing))
	sig, err := rsa.SignPKCS1v15(rand.Reader, f.key, crypto.SHA256, sum[:])
	if err != nil {
		return "", err
	}
	return signing + "." + enc.EncodeToString(sig), nil
}
