// Screenshots of the main screens at phone and desktop width. They land in
// e2e/test-results/ (and in CI's artifacts), so you can look at a change
// without running anything. Add your own screens here.
const { test, expect, signUp } = require('./fixtures');

for (const [name, viewport] of [
  ['phone', { width: 390, height: 844 }],
  ['desktop', { width: 1280, height: 800 }],
]) {
  test(`screens at ${name} width`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const shot = (label) => page.screenshot({ path: test.info().outputPath(`${name}-${label}.png`), fullPage: true });

    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await shot('0-introduction');

    await page.goto('/app/');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await shot('1-sign-in');

    await signUp(page);
    expect((await page.request.put('/api/space/me', { data: { name: 'Fran' } })).status()).toBe(200);
    await page.reload();
    await shot('2-empty');
    // Sample places through the API, with visits and a rating.
    for (const [name, area, cuisine, days, stars] of [
      ['Hanabi Grill', 'Station East', 'Hamburg steak', 3, 5],
      ['Gyoza Komachi', 'Station East', 'Gyoza', 12, 4],
      ['Ramen Tsubaki', 'Old Town', 'Ramen', 20, 3],
      ['A restaurant with a very long name that has to wrap somewhere', 'Lakeside', 'Eel', 75, 5],
      ['Blue Sky Café', 'Riverside', 'Café', null, 0],
    ]) {
      const visit = days === null ? undefined : { visited_on: new Date(Date.now() - days * 864e5).toISOString().slice(0, 10) };
      const res = await page.request.post('/api/restaurants', { data: { name, area, cuisine, visit, stars } });
      expect(res.status()).toBe(201);
    }
    await page.reload();
    await expect(page.getByRole('list', { name: 'Been there' }).getByRole('listitem')).toHaveCount(4);
    await shot('3-restaurants');

    await page.getByRole('link', { name: /Hanabi Grill/ }).click();
    await expect(page.getByRole('heading', { name: 'Hanabi Grill' })).toBeVisible();
    await shot('4-restaurant');

    await page.goto('/app/#/add');
    await expect(page.getByRole('heading', { name: 'Add a place' })).toBeVisible();
    await shot('5-add');

    // Shifts, with a few days of this month.
    const d = new Date();
    const month = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    await page.request.put('/api/shifts/settings', { data: { person_name: 'Sam', name_on_sheet: 'オトル サマンタ' } });
    const codes = ['7F', '9F', '13F', '22F', '普', '13F', '法', '有休', '13F', '22F'];
    const res = await page.request.put('/api/shifts/month', {
      data: { month, days: codes.map((code, i) => ({ date: `${month}-${String(i + 1).padStart(2, '0')}`, code })) },
    });
    expect(res.status()).toBe(200);
    await page.getByRole('link', { name: 'Shifts', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Sam’s shifts' })).toBeVisible();
    await shot('6-shifts');
    await page.getByRole('link', { name: 'Edit by hand' }).click();
    await expect(page.getByRole('list', { name: 'Days' })).toBeVisible();
    await shot('7-shift-edit');

    await page.getByRole('link', { name: 'Us', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'The two of you' })).toBeVisible();
    await shot('8-us');

    // Nothing may overflow sideways.
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
}
