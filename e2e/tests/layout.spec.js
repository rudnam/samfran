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

test('the Android app is on Us, from the Play Store', async ({ page }) => {
  await signUp(page);
  await page.getByRole('navigation').getByRole('link', { name: 'Us', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Recent changes' })).toBeVisible();
  await expect(page.getByText('Get the Android app')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Google Play' })).toHaveAttribute('href', 'https://play.google.com/apps/internaltest/4700129288045289290');
});
