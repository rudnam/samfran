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
    // animations: 'disabled' jumps entrances to their end, so each picture shows the settled screen.
    const shot = (label) =>
      page.screenshot({ path: test.info().outputPath(`${name}-${label}.png`), fullPage: true, animations: 'disabled' });

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
    for (const [name, area, cuisine, category, days, stars] of [
      ['Hanabi Grill', 'Station East', 'Hamburg steak', 'eat', 3, 5],
      ['Gyoza Komachi', 'Station East', 'Gyoza', 'eat', 12, 4],
      ['Ramen Tsubaki', 'Old Town', 'Ramen', 'eat', 20, 3],
      ['A restaurant with a very long name that has to wrap somewhere', 'Lakeside', 'Eel', 'eat', 75, 5],
      ['Castle Park', 'Old Town', 'Park', 'nature', 30, 4],
      ['Blue Sky Café', 'Riverside', 'Café', 'eat', null, 0],
      ['Hilltop Shrine', 'North Hills', 'Shrine', 'culture', null, 0],
      ['Sand Dunes', 'South Coast', 'Dunes', 'nature', null, 0],
      ['Lakeside Onsen', 'Lakeside', 'Onsen', 'relax', null, 0],
      ['City Aquarium', 'Harbour', 'Aquarium', 'fun', null, 0],
    ]) {
      const visit = days === null ? undefined : { visited_on: new Date(Date.now() - days * 864e5).toISOString().slice(0, 10) };
      const res = await page.request.post('/api/restaurants', { data: { name, area, cuisine, category, visit, stars } });
      expect(res.status()).toBe(201);
    }
    await page.reload();
    await expect(page.getByRole('list', { name: 'Been there' }).getByRole('listitem')).toHaveCount(5);
    await shot('3-restaurants');
    // Want to go, filtered to one category.
    await page.getByRole('link', { name: /Want to go/ }).click();
    await page.getByRole('radio', { name: /Nature/ }).click();
    await expect(page.getByRole('list', { name: 'Want to go' }).getByRole('listitem')).toHaveCount(1);
    await shot('3b-want-nature');
    await page.getByRole('radio', { name: /All/ }).click();
    await shot('3c-want-all');
    await page.getByRole('link', { name: /Been there/ }).click();

    await page.getByRole('link', { name: /Hanabi Grill/ }).click();
    await expect(page.getByRole('heading', { name: 'Hanabi Grill' })).toBeVisible();
    await shot('4-restaurant');
    await page.getByRole('button', { name: 'Delete this place' }).click();
    await expect(page.getByRole('dialog', { name: 'Delete Hanabi Grill?' })).toBeVisible();
    await shot('4b-delete-dialog');
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
    await page.getByRole('button', { name: /^Delete the visit on/ }).click();
    await expect(page.getByRole('button', { name: 'Undo' })).toBeVisible();
    await shot('4c-undo');
    await page.getByRole('button', { name: 'Undo' }).click();

    await page.goto('/app/#/add');
    await expect(page.getByRole('heading', { name: 'Add a place' })).toBeVisible();
    await shot('5-add');
    await page.route('**/api/restaurants/suggest?*', (route) =>
      route.fulfill({
        json: [
          { name: 'Gyoza Komachi', area: 'Hamamatsu Chuo', street: 'Kajimachi', kind: 'restaurant', category: 'eat', lat: 34.704, lng: 137.734 },
          { name: 'Gyoza no Ōshō', area: 'Hamamatsu Chuo', street: 'Sunayama', kind: 'restaurant', category: 'eat', lat: 34.703, lng: 137.73 },
          { name: 'Gyoza Café', area: 'Hamamatsu Naka', street: '', kind: 'cafe', category: 'eat', lat: 34.71, lng: 137.72 },
          { name: 'Gyoza Park', area: 'Hamamatsu Naka', street: '', kind: 'park', category: 'nature', lat: 34.72, lng: 137.72 },
          { name: 'Gyoza Shrine', area: 'Hamamatsu Kita', street: '', kind: 'place_of_worship', category: 'culture', lat: 34.8, lng: 137.7 },
        ],
      }),
    );
    await page.getByLabel('Name', { exact: true }).fill('Gyoza');
    await expect(page.getByRole('list', { name: 'Matching places' }).getByRole('listitem')).toHaveCount(5);
    await shot('5b-add-search');
    await page.getByRole('button', { name: /Gyoza Park/ }).click();
    await expect(page.getByLabel('Kind')).toHaveValue('Park');
    await expect(page.getByRole('radio', { name: 'Nature & parks' })).toHaveAttribute('aria-checked', 'true');
    await shot('5c-add-picked');

    // Shifts, with a few days of this month.
    const d = new Date();
    const month = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    await page.request.put('/api/shifts/settings', { data: { person_name: 'Sam', name_on_sheet: 'オトル サマンタ' } });
    // The whole month, so the calendar is full and there's a next shift whatever today is.
    const codes = ['7F', '9F', '13F', '22F', '普', '13F', '法', '有休', '13F', '22F'];
    const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
    const res = await page.request.put('/api/shifts/month', {
      data: {
        month,
        days: Array.from({ length: last }, (_, i) => {
          const code = codes[i % codes.length];
          // Who's on the same shift, on working days.
          const together = /^\d/.test(code) ? [['タナカ ユイ', 'スズキ ケン'], ['サトウ ミホ']][i % 2] : undefined;
          // Others on other shifts: the server keeps those whose hours overlap.
          const others = /^\d/.test(code)
            ? [
                { name: 'ヤマダ ハナ', code: '9F' },
                { name: 'コバヤシ リク', code: '7D' },
                { name: 'サトウ ミオ', code: '17D' },
                { name: 'ヨル タロウ', code: '22F', day_offset: -1 },
                { name: 'アサ ミキ', code: '6D', day_offset: 1 },
              ]
            : undefined;
          return { date: `${month}-${String(i + 1).padStart(2, '0')}`, code, with: together, others };
        }),
      },
    });
    expect(res.status()).toBe(200);
    await page.getByRole('link', { name: 'Shifts', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Sam’s shifts' })).toBeVisible();
    await expect(page.getByRole('group', { name: 'Calendar' })).toBeVisible();
    await shot('6-shifts');
    await page.getByRole('radio', { name: 'List' }).click();
    await expect(page.getByRole('list', { name: 'Shifts' })).toBeVisible();
    await shot('6b-shifts-list');
    // Everyone there, not only on the same shift: a late shift's day.
    await page.getByRole('radio', { name: 'Calendar' }).click();
    await page.getByRole('button', { name: 'Everyone there' }).click();
    await page.getByRole('group', { name: 'Calendar' }).getByRole('button', { name: /^3 / }).click();
    await expect(page.locator('.cal-detail').getByRole('list', { name: 'Everyone there' })).toBeVisible();
    await shot('6c-shifts-everyone');
    await page.getByRole('button', { name: 'Everyone there' }).click();
    await page.getByRole('link', { name: 'Edit by hand' }).click();
    await expect(page.getByRole('list', { name: 'Days' })).toBeVisible();
    await shot('7-shift-edit');
    // This month's 締め申請通知 changed: only the last day.
    await page.getByRole('button', { name: 'Remove closing day 1' }).click();
    await expect(page.getByRole('button', { name: 'Use the usual days' })).toBeVisible();
    await shot('7a-shift-edit-closing');
    await page.goto('/app/#/shifts/calendar');
    await page.getByRole('button', { name: 'Turn on the calendar link' }).click();
    await expect(page.getByRole('link', { name: 'Open Google Calendar' })).toBeVisible();
    await shot('7a2-google-calendar');

    // The bus alarm: first time, then with a stop chosen.
    await page.getByRole('link', { name: 'Bus', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'How it works' })).toBeVisible();
    await shot('7b-bus-setup');
    expect((await page.request.put('/api/bus/stop', { data: { name: '葵西小学校入口', lat: 34.76966, lng: 137.71207, alert_meters: 800 } })).status()).toBe(200);
    expect((await page.request.post('/api/bus/stops', { data: { name: '静岡駅', lat: 34.9717, lng: 138.3889, shifts: ['Early', 'Middle'] } })).status()).toBe(201);
    await page.reload();
    await expect(page.getByRole('button', { name: 'Try it now' })).toBeVisible();
    await shot('7c-bus');

    await page.getByRole('link', { name: 'Us', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'The two of you' })).toBeVisible();
    await shot('8-us');

    // The same screens in dark, picked on Us.
    await page.getByLabel('Appearance').selectOption('dark');
    await shot('9-us-dark');
    await page.getByRole('link', { name: 'Places' }).click();
    await expect(page.getByRole('heading', { name: 'Our spots' })).toBeVisible();
    await shot('10-food-dark');
    await page.getByRole('link', { name: 'Bus', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Try it now' })).toBeVisible();
    await shot('11-bus-dark');

    // Nothing may overflow sideways.
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
}
