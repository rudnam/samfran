// Shared fixtures. Import { test, expect } from here instead of from
// @playwright/test so every test gets them.
const base = require('@playwright/test');

// The bus stop's map, from a fake: tests don't reach the internet.
const fakeMap =
  '<!doctype html><body style="margin:0;background:#e8eaed;font:14px sans-serif;display:grid;place-items:center;height:100vh">' +
  '<div style="width:22px;height:22px;border-radius:50% 50% 50% 0;transform:rotate(-45deg);background:#ea4335"></div></body>';

// Google Maps' script for the places' map (web/src/map/main.ts), from a
// fake that puts each overlay (a pin) on the page, spread out by position,
// and calls back as Google does.
const fakeMapsScript = `
(() => {
  const el = () => document.getElementById('map');
  class LatLng { constructor(lat, lng) { this.lat = () => lat; this.lng = () => lng; } }
  class Map {
    constructor(div) { this.div = div; this.listeners = {}; div.style.position = 'relative'; div.style.background = '#e8eaed';
      div.addEventListener('click', () => (this.listeners.click || []).forEach((f) => f())); }
    addListener(name, f) { (this.listeners[name] = this.listeners[name] || []).push(f); }
    setCenter() {} setZoom() {} fitBounds() {}
  }
  class OverlayView {
    setMap(map) {
      if (map) { this.getPanes = () => ({ overlayMouseTarget: el() });
        this.getProjection = () => ({ fromLatLngToDivPixel: (p) => ({ x: 60 + ((p.lng() * 1000) % 250), y: 80 + ((p.lat() * 1000) % 300) }) });
        this.onAdd(); this.draw(); }
      else this.onRemove();
    }
  }
  class LatLngBounds { extend() {} }
  window.google = { maps: { Map, OverlayView, LatLng, LatLngBounds } };
  const cb = new URL(document.currentScript.src).searchParams.get('callback');
  setTimeout(() => window[cb](), 0);
})();`;

const test = base.test.extend({
  // Fails the test if the page throws or logs a console error (which includes
  // Content-Security-Policy violations).
  page: async ({ page }, use) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
    page.on('console', (m) => {
      // The browser logs every 4xx response as an error, but some are expected:
      // a 401 from /api/me means nobody is signed in, and a 400, 401, 403 or
      // 409 (e.g. a bad sign-in, a used reset link, or a refused change) is
      // shown in the form. Tests assert on what the user sees.
      if (m.type() === 'error' && !/status of 40[0139] /.test(m.text())) errors.push('console: ' + m.text());
    });
    // Typing a place's name searches Google or OpenStreetMap through the
    // server; tests never reach either. A test that wants matches routes the search itself.
    await page.route('**/api/restaurants/search?*', (route) => route.fulfill({ json: [] }));
    await page.route('**/api/restaurants/suggest?*', (route) => route.fulfill({ json: [] }));
    await page.route('https://www.google.com/maps?*', (route) => route.fulfill({ contentType: 'text/html', body: fakeMap }));
    await page.route('https://maps.googleapis.com/maps/api/js?*', (route) =>
      route.fulfill({ contentType: 'text/javascript', body: fakeMapsScript }),
    );
    await use(page);
    base.expect(errors, 'errors in the browser').toEqual([]);
  },
});

/**
 * Where the browser says the device is, for Nearby: at lat/lng, or, with
 * none, location turned down. (The app is at http://server:8080 in Docker,
 * where the browser's own location needs https, so this stands in for it.)
 */
async function standAt(page, lat, lng) {
  await page.addInitScript(([lat, lng]) => {
    navigator.geolocation.getCurrentPosition = (ok, fail) =>
      setTimeout(() =>
        lat === null
          ? fail({ code: 1, PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3, message: 'denied' })
          : ok({ coords: { latitude: lat, longitude: lng, accuracy: 20 }, timestamp: Date.now() }),
      );
  }, [lat ?? null, lng ?? null]);
}

/** Shows one category's places (or 'All places'), from the category menu above the list. */
async function pickCategory(page, name) {
  await page.getByRole('button', { name: /^Category:/ }).click();
  await page.getByRole('radiogroup', { name: 'Category' }).getByRole('radio', { name }).click();
}

let n = 0;
/** A unique email per call, so parallel tests never share an account. */
function uniqueEmail() {
  return `user-${Date.now()}-${process.pid}-${n++}@example.com`;
}

const PASSWORD = 'a long password';

/**
 * Creates a fresh account through the API (the browser context gets its
 * session cookie) and opens the app signed in.
 */
async function signUp(page) {
  const email = uniqueEmail();
  const res = await page.request.post('/api/signup', { data: { email, password: PASSWORD } });
  base.expect(res.status(), await res.text()).toBe(201);
  // Open the emailed link, so screens look as they do for a settled account.
  await page.request.get(await confirmationLink(page.request, email));
  await page.goto('/app/');
  await base.expect(page.getByRole('heading', { name: 'Our spots' })).toBeVisible();
  return email;
}

const MAIL_API = process.env.MAIL_API || 'http://mail:8025'; // the mail catcher in e2e/compose.yml

/** The link in the newest email to "to" that has one matching pattern, read from the mail catcher. */
async function emailedLink(request, to, pattern) {
  let link = null;
  await base
    .expect(async () => {
      const found = await (await request.get(`${MAIL_API}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`)).json();
      for (const m of found.messages) {
        const text = (await (await request.get(`${MAIL_API}/api/v1/message/${m.ID}`)).json()).Text;
        link = text.match(pattern);
        if (link) break;
      }
      base.expect(link, `no link like ${pattern} emailed to ${to}`).not.toBeNull();
    })
    .toPass({ timeout: 10_000 });
  return link[0];
}

/** The newest confirmation link the app emailed to "to". */
function confirmationLink(request, to) {
  return emailedLink(request, to, /https?:\/\/\S+\/api\/verify-email\?token=[\w-]+/);
}

/** The newest password reset link the app emailed to "to". */
function resetLink(request, to) {
  return emailedLink(request, to, /https?:\/\/\S+\/app\/#\/reset\?token=[\w-]+/);
}

module.exports = { test, expect: base.expect, signUp, standAt, pickCategory, uniqueEmail, PASSWORD, confirmationLink, resetLink };
