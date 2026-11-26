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
    // Allowed already (or not): the add form only uses a location it may have without asking.
    const query = navigator.permissions.query.bind(navigator.permissions);
    navigator.permissions.query = (d) =>
      d.name === 'geolocation' ? Promise.resolve({ state: lat === null ? 'denied' : 'granted' }) : query(d);
  }, [lat ?? null, lng ?? null]);
}

/** Shows one category's places (or 'All places'), from the category menu above the list. */
async function pickCategory(page, name) {
  await page.getByRole('button', { name: /^Category:/ }).click();
  await page.getByRole('radiogroup', { name: 'Category' }).getByRole('radio', { name }).click();
}

/** Orders the list ('Newest', 'Nearby', 'Top rated', 'A–Z'), from the order menu above it. */
async function pickOrder(page, name) {
  await page.getByRole('button', { name: /^Order:/ }).click();
  await page.getByRole('radiogroup', { name: 'Order' }).getByRole('radio', { name }).click();
}

let n = 0;
/** A unique email per call, so parallel tests never share an account. */
/**
 * A JPEG that looks a little like a photo (a sky, a hill, a sun), drawn in
 * the page, as a Buffer: { name, mimeType, buffer } for setInputFiles or a
 * multipart upload. hue picks its colours.
 */
async function photo(page, { width = 1600, height = 1200, hue = 30, name = 'photo.jpg' } = {}) {
  const b64 = await page.evaluate(
    async ({ width, height, hue }) => {
      const c = document.createElement('canvas');
      c.width = width;
      c.height = height;
      const g = c.getContext('2d');
      const sky = g.createLinearGradient(0, 0, 0, height);
      sky.addColorStop(0, `hsl(${hue + 180}, 60%, 72%)`);
      sky.addColorStop(1, `hsl(${hue + 200}, 50%, 88%)`);
      g.fillStyle = sky;
      g.fillRect(0, 0, width, height);
      g.fillStyle = `hsl(${hue + 40}, 90%, 62%)`;
      g.beginPath();
      g.arc(width * 0.72, height * 0.3, height * 0.12, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = `hsl(${hue}, 45%, 38%)`;
      g.beginPath();
      g.moveTo(0, height);
      g.quadraticCurveTo(width * 0.35, height * 0.35, width, height * 0.75);
      g.lineTo(width, height);
      g.fill();
      const blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.95));
      const bytes = new Uint8Array(await blob.arrayBuffer());
      let s = '';
      for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      return btoa(s);
    },
    { width, height, hue },
  );
  return { name, mimeType: 'image/jpeg', buffer: Buffer.from(b64, 'base64') };
}

/** Adds a photo to a visit through the API, as the apps do: a thumbnail, then the original. */
async function addPhoto(page, placeId, visitId, opts = {}) {
  const original = await photo(page, opts);
  const thumb = await photo(page, { ...opts, width: 480, height: Math.round((480 * (opts.height ?? 1200)) / (opts.width ?? 1600)), name: 'thumb.jpg' });
  const res = await page.request.post(`/api/restaurants/${placeId}/visits/${visitId}/photos`, {
    multipart: { width: String(opts.width ?? 1600), height: String(opts.height ?? 1200), thumb, photo: original },
  });
  if (res.status() !== 201) throw new Error(`add photo: ${res.status()} ${await res.text()}`);
  return { place: await res.json(), original };
}

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
  await base.expect(page.getByRole('heading', { name: 'Our places' })).toBeVisible();
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

module.exports = { test, expect: base.expect, signUp, standAt, pickCategory, pickOrder, photo, addPhoto, uniqueEmail, PASSWORD, confirmationLink, resetLink };
