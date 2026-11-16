package server_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"
)

// mcpCall sends one JSON-RPC request to /mcp the way an MCP client does: a
// bearer token, no cookies, no Origin.
func mcpCall(t *testing.T, base, token, method string, params any) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
	req, _ := http.NewRequest("POST", base+"/mcp", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// tool calls an MCP tool and returns its structured result, or its error text.
func tool(t *testing.T, base, token, name string, args any) (map[string]any, string) {
	t.Helper()
	code, out := mcpCall(t, base, token, "tools/call", map[string]any{"name": name, "arguments": args})
	if code != 200 || out["error"] != nil {
		t.Fatalf("%s: %d %v", name, code, out)
	}
	res := out["result"].(map[string]any)
	if res["isError"] == true {
		return nil, res["content"].([]any)[0].(map[string]any)["text"].(string)
	}
	return res["structuredContent"].(map[string]any), ""
}

// connect signs c in to Claude the way a connector does and returns its
// access token.
func connect(t *testing.T, base string, c *client) string {
	t.Helper()
	const callback = "https://claude.example/api/mcp/auth_callback"
	_, reg := oauthPost(t, base+"/oauth/register", map[string]any{"client_name": "Claude", "redirect_uris": []string{callback}})
	clientID := reg["client_id"].(string)
	verifier := strings.Repeat("v", 50)
	var out map[string]string
	if code := c.do("POST", "/api/mcp/authorize", map[string]any{
		"client_id": clientID, "redirect_uri": callback, "code_challenge": pkce(verifier), "state": "s", "allow": true}, &out); code != 200 {
		t.Fatalf("authorize: %d", code)
	}
	back, _ := url.Parse(out["redirect_to"])
	_, tok := oauthPost(t, base+"/oauth/token", url.Values{"grant_type": {"authorization_code"}, "code": {back.Query().Get("code")},
		"code_verifier": {verifier}, "redirect_uri": {callback}, "client_id": {clientID}})
	return tok["access_token"].(string)
}

func TestMCPTools(t *testing.T) {
	ts := newTestServer(t, closed)
	fran, sam := newClient(t, ts), newClient(t, ts)
	fran.signup("fran@example.com")
	fran.do("POST", "/api/space/invites", map[string]string{"email": "sam@example.com"}, nil)
	sam.signup("sam@example.com")
	fran.do("PUT", "/api/shifts/settings", map[string]any{"person_name": "Sam", "name_on_sheet": "オトル サマンタ"}, nil)
	token := connect(t, ts.URL, fran)

	// The tools, and instructions that say to confirm before saving shifts.
	_, init := mcpCall(t, ts.URL, token, "initialize", map[string]any{"protocolVersion": "2025-06-18"})
	if !strings.Contains(init["result"].(map[string]any)["instructions"].(string), "Only after they confirm") {
		t.Errorf("instructions: %v", init)
	}
	_, list := mcpCall(t, ts.URL, token, "tools/list", nil)
	if n := len(list["result"].(map[string]any)["tools"].([]any)); n != 8 {
		t.Errorf("%d tools", n)
	}

	// Shifts: settings, then a month read from a photo, saved.
	st, _ := tool(t, ts.URL, token, "get_shift_settings", map[string]any{})
	if st["name_on_sheet"] != "オトル サマンタ" || st["lengths"].(map[string]any)["F"] != 9.0 || st["feed_path"] != "" {
		t.Fatalf("settings: %v", st)
	}
	st, _ = tool(t, ts.URL, token, "set_shift_lengths", map[string]any{"lengths": map[string]float64{"G": 10}})
	if l := st["lengths"].(map[string]any); l["G"] != 10.0 || l["F"] != 9.0 {
		t.Errorf("set lengths: %v", st)
	}
	if _, msg := tool(t, ts.URL, token, "set_shift_lengths", map[string]any{"lengths": map[string]float64{"ab": 3}}); msg == "" {
		t.Error("a bad letter was accepted")
	}
	saved, _ := tool(t, ts.URL, token, "save_month_shifts", map[string]any{"month": "2026-11", "days": []map[string]string{
		{"date": "2026-11-01", "code": "普"}, {"date": "2026-11-02", "code": "13F"}, {"date": "2026-11-06", "code": "22F"}}})
	days := saved["days"].([]any)
	if len(days) != 3 || days[2].(map[string]any)["end"] != "2026-11-07T07:00:00+09:00" {
		t.Fatalf("saved: %v", saved)
	}
	if _, msg := tool(t, ts.URL, token, "save_month_shifts", map[string]any{"month": "2026-11", "days": []map[string]string{{"date": "2026-12-01", "code": "7F"}}}); msg == "" {
		t.Error("a day outside the month was saved")
	}
	// Sam sees them in the app: the same space.
	var m struct{ Days []struct{ Code string } }
	if sam.do("GET", "/api/shifts?month=2026-11", nil, &m); len(m.Days) != 3 {
		t.Errorf("sam sees %+v", m)
	}

	// Restaurants: add with a visit and Fran's rating, log another, rate.
	today := time.Now().Format(time.DateOnly)
	r, _ := tool(t, ts.URL, token, "add_restaurant", map[string]any{"name": "Taco Loco", "cuisine": "Mexican", "visited_on": today, "stars": 4})
	id := r["id"].(float64)
	if r["visit_count"] != 1.0 || r["ratings"].([]any)[0].(map[string]any)["name"] != "fran" {
		t.Fatalf("add: %v", r)
	}
	if _, msg := tool(t, ts.URL, token, "add_restaurant", map[string]any{"name": "x", "map_url": "javascript:alert(1)"}); !strings.Contains(msg, "web address") {
		t.Errorf("bad map link: %q", msg)
	}
	r, _ = tool(t, ts.URL, token, "log_visit", map[string]any{"restaurant_id": id, "visited_on": today, "note": "green salsa"})
	if r["visit_count"] != 2.0 {
		t.Errorf("visit: %v", r)
	}
	if _, msg := tool(t, ts.URL, token, "rate_restaurant", map[string]any{"restaurant_id": 999999, "stars": 3}); msg != `{"error":"no such restaurant"}` {
		t.Errorf("unknown restaurant: %q", msg)
	}
	tool(t, ts.URL, token, "add_restaurant", map[string]any{"name": "Blue Sky Café"})
	want, _ := tool(t, ts.URL, token, "list_restaurants", map[string]any{"list": "want"})
	if l := want["restaurants"].([]any); len(l) != 1 || l[0].(map[string]any)["name"] != "Blue Sky Café" {
		t.Errorf("want list: %v", want)
	}
}
