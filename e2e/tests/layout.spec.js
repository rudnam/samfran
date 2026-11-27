// Things that stay put: the page doesn't nudge sideways when a scrollbar
// comes or goes, and cards that load on their own (the next shift, the
// Android app) don't drop in above what's already showing.
const { test, expect, signUp } = require('./fixtures');

const slow = (ms) => async (route) => {
  await new Promise((r) => setTimeout(r, ms));
  await route.fallback();
};

test('a short page and a long one line up: room is kept for the scrollbar', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 600 });
  await signUp(page);
  const where = () => page.evaluate(() => ({ left: document.querySelector('main').getBoundingClientRect().left, width: document.documentElement.clientWidth }));

  await page.getByRole('navigation').getByRole('link', { name: 'Us', exact: true }).click();
  await page.waitForTimeout(400); // past the page's entrance
  const long = await where();
  expect(await page.evaluate(() => document.documentElement.scrollHeight > innerHeight), 'Us is longer than the window').toBe(true);

  // Places, empty: shorter than the window, so it has nothing to scroll.
  await page.getByRole('navigation').getByRole('link', { name: 'Places', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Our places' })).toBeVisible();
  await page.waitForTimeout(400);
  expect(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight), 'Places is shorter than the window').toBe(true);
  expect(await where()).toEqual(long);
});

test('the next shift shows up with the month, not after it', async ({ page }) => {
  await signUp(page);
  // Nothing left this month, so the next shift is found in next month's.
  const d = new Date();
  const next = new Date(d.getFullYear(), d.getMonth() + 1, 1);
  const month = `${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}`;
  await page.request.put('/api/shifts/settings', { data: { person_name: 'Sam', name_on_sheet: 'オトル サマンタ' } });
  expect((await page.request.put('/api/shifts/month', { data: { month, days: [{ date: `${month}-03`, code: '9F' }] } })).status()).toBe(200);
  // And next month answers slowly.
  await page.route(`**/api/shifts?month=${month}`, slow(1000));

  await page.getByRole('navigation').getByRole('link', { name: 'Shifts', exact: true }).click();
  await expect(page.getByText('No shifts for this month')).toBeVisible();
  // Already there when this month's (empty) days appear: nothing below it moves.
  expect(await page.locator('.next-shift').count()).toBe(1);
  await expect(page.locator('.next-shift')).toContainText('Next shift');
});
test('the font is preloaded, cached, and ready across refreshes', async ({ context, request }) => {
  const fontPath = '/fonts/plus-jakarta-sans-latin.woff2';
  const res = await request.get(fontPath);
  expect(res.status()).toBe(200);
  expect(res.headers()['content-type']).toBe('font/woff2');
  expect(res.headers()['cache-control']).toContain('immutable');

  // Use a page without fixtures.js's page.route() handlers so Chromium keeps its HTTP cache active.
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/status of 40[0139] /.test(m.text())) errors.push('console: ' + m.text());
  });
  try {
    for (const url of ['/', '/app/']) {
      await page.goto(url);
      await expect(page.locator(`link[rel="preload"][as="font"][type="font/woff2"][crossorigin][href="${fontPath}"]`)).toHaveCount(1);
      const loadedFirst = await page.evaluate(async () => {
        await document.fonts.ready;
        return [...document.fonts].some((f) => f.family.includes('Plus Jakarta Sans') && f.status === 'loaded');
      });
      expect(loadedFirst, `${url} loaded Plus Jakarta Sans`).toBe(true);

      await page.reload();
      const reloadCheck = await page.evaluate(async (path) => {
        await document.fonts.ready;
        const loaded = [...document.fonts].some((f) => f.family.includes('Plus Jakarta Sans') && f.status === 'loaded');
        const entries = performance.getEntriesByType('resource').filter((e) => e.name.endsWith(path));
        return { loaded, transferSizes: entries.map((e) => e.transferSize) };
      }, fontPath);
      expect(reloadCheck.loaded, `${url} loaded Plus Jakarta Sans after reload`).toBe(true);
      expect(reloadCheck.transferSizes.length, `${url} font timing entry after reload`).toBeGreaterThan(0);
      expect(reloadCheck.transferSizes.every((s) => s === 0), `${url} served font from cache after reload`).toBe(true);
    }
    expect(errors, 'errors in the browser').toEqual([]);
  } finally {
    await page.close();
  }
});
