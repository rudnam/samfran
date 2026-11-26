// Screenshots of the main screens at phone and desktop width. They land in
// e2e/test-results/ (and in CI's artifacts), so you can look at a change
// without running anything. Add your own screens here.
const { test, expect, addPhoto, signUp, standAt, pickCategory, pickOrder } = require('./fixtures');

for (const [name, viewport] of [
  ['phone', { width: 390, height: 844 }],
  ['desktop', { width: 1280, height: 800 }],
]) {
  test(`screens at ${name} width`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await standAt(page, 34.7038, 137.7349); // Hamamatsu Station
    await page.route('**/api/restaurants/map', (route) => route.fulfill({ json: { key: 'test-key' } }));
    // animations: 'disabled' jumps entrances to their end, so each picture shows the settled screen.
    // Photos load lazily: each picture waits for those on the page.
    const shot = async (label) => {
      await page.evaluate(() => Promise.all([...document.images].map((i) => (i.loading = 'eager') && i.decode().catch(() => {}))));
      await page.screenshot({ path: test.info().outputPath(`${name}-${label}.png`), fullPage: true, animations: 'disabled' });
    };

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
    const placeIds = {};
    for (const [name, area, cuisine, category, days, stars, lat, lng] of [
      ['Hanabi Grill', 'Station East', 'Hamburg steak', 'eat', 3, 5, 34.7046, 137.7362],
      ['Gyoza Komachi', 'Station East', 'Gyoza', 'eat', 12, 4, 34.7031, 137.7371],
      ['Ramen Tsubaki', 'Old Town', 'Ramen', 'eat', 20, 3, 34.7082, 137.7305],
      ['A restaurant with a very long name that has to wrap somewhere', 'Lakeside', 'Eel', 'eat', 75, 5],
      ['Castle Park', 'Old Town', 'Park', 'nature', 30, 4, 34.7108, 137.7249],
      ['Blue Sky Café', 'Riverside', 'Café', 'eat', null, 0, 34.6991, 137.7402],
      ['Hilltop Shrine', 'North Hills', 'Shrine', 'culture', null, 0, 34.7354, 137.7187],
      ['Sand Dunes', 'South Coast', 'Dunes', 'nature', null, 0, 34.656, 137.731],
      ['Lakeside Onsen', 'Lakeside', 'Onsen', 'relax', null, 0, 34.7651, 137.5911],
      ['City Aquarium', 'Harbour', 'Aquarium', 'fun', null, 0],
    ]) {
      const visit = days === null ? undefined : { visited_on: new Date(Date.now() - days * 864e5).toISOString().slice(0, 10) };
      const res = await page.request.post('/api/restaurants', { data: { name, area, cuisine, category, visit, stars, lat, lng } });
      expect(res.status()).toBe(201);
      placeIds[name] = (await res.json()).id;
    }
    // Photos: the newest is the place's picture on the list. Hanabi Grill went twice, with photos each time.
    const hanabi = placeIds['Hanabi Grill'];
    const again = await page.request.post(`/api/restaurants/${hanabi}/visits`, {
      data: { visited_on: new Date(Date.now() - 40 * 864e5).toISOString().slice(0, 10), note: 'First time. The demi-glace hamburg, and a long wait for a table.' },
    });
    const visitsOf = (await again.json()).visits;
    for (const hue of [20, 35, 10, 50]) await addPhoto(page, hanabi, visitsOf[0].id, { hue });
    await addPhoto(page, hanabi, visitsOf[1].id, { hue: 160, width: 1200, height: 1600 });
    const castle = await (await page.request.get(`/api/restaurants/${placeIds['Castle Park']}`)).json();
    await addPhoto(page, castle.id, castle.visits[0].id, { hue: 110 });
    await page.reload();
    await expect(page.getByRole('list', { name: 'Been there' }).getByRole('listitem')).toHaveCount(5);
    // Add says what it does at every width, as on Android: a labelled button, not a bare +.
    await expect(page.getByRole('link', { name: 'Add a place' }).filter({ visible: true })).toContainText('Add a place');
    await shot('3-restaurants');
    // Want to go, filtered to one category.
    await page.getByRole('link', { name: /Want to go/ }).click();
    await page.getByRole('button', { name: /^Category:/ }).click();
    await shot('3a-category-menu');
    await page.getByRole('radiogroup', { name: 'Category' }).getByRole('radio', { name: /Nature & parks/ }).click();
    await expect(page.getByRole('list', { name: 'Want to go' }).getByRole('listitem')).toHaveCount(1);
    await shot('3b-want-nature');
    await pickCategory(page, /All places/);
    await shot('3c-want-all');
    // Nearest first, from Hamamatsu Station, then on the map (Google is a fake: fixtures.js).
    await page.getByRole('button', { name: /^Order:/ }).click();
    await shot('3c2-order-menu');
    await page.getByRole('radiogroup', { name: 'Order' }).getByRole('radio', { name: 'Nearby' }).click();
    await expect(page.getByRole('list', { name: 'Want to go' }).getByRole('listitem').first()).toContainText('Blue Sky Café');
    await shot('3d-want-nearby');
    await page.getByRole('button', { name: 'Map' }).click();
    await page.frameLocator('iframe[title="Map of the places"]').getByRole('button', { name: 'Hilltop Shrine' }).click();
    await expect(page.locator('.map-card')).toContainText('Hilltop Shrine');
    await shot('3e-want-map');
    await page.getByRole('button', { name: 'Map' }).click(); // back to the list
    await pickOrder(page, 'Newest'); // newest first again
    await page.getByRole('link', { name: /Been there/ }).click();

    await page.getByRole('link', { name: /Hanabi Grill/ }).click();
    await expect(page.getByRole('heading', { name: 'Hanabi Grill' })).toBeVisible();
    await shot('4-restaurant');
    await page.getByLabel('Add a comment').fill('The demi-glace was even better than last time. Next time: the cheese one.');
    await page.getByRole('button', { name: 'Post' }).click();
    await expect(page.getByRole('list', { name: 'Comments' }).getByRole('listitem')).toHaveCount(1);
    await shot('4a-comments');
    await page.getByRole('button', { name: 'Delete this place' }).click();
    await expect(page.getByRole('dialog', { name: 'Delete Hanabi Grill?' })).toBeVisible();
    await shot('4b-delete-dialog');
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
    await page.getByRole('button', { name: 'Went again' }).click();
    await expect(page.getByLabel('When did you go?')).toBeVisible();
    await shot('4c-went-again');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await page.getByRole('button', { name: /^Edit the visit on / }).first().click();
    await shot('4d-visit-edit');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await page.getByRole('button', { name: /^Photo 1 of 4/ }).click();
    await expect(page.getByRole('img', { name: 'Photo 1 of 4' })).toHaveJSProperty('complete', true);
    await shot('4f-photo-viewer');
    await page.keyboard.press('Escape');
    // A want-to-go place: Mark as been asks when.
    await page.getByRole('link', { name: 'Our places' }).click();
    await page.getByRole('link', { name: /Want to go/ }).click();
    await page.getByRole('link', { name: /Blue Sky Café/ }).click();
    await page.getByRole('button', { name: 'Mark as been' }).click();
    await expect(page.getByLabel('When did you go?')).toBeVisible();
    await shot('4e-mark-been');

    await page.goto('/app/#/add');
    await expect(page.getByRole('heading', { name: 'Add a place' })).toBeVisible();
    await shot('5-add');
    // As it's seen, not the whole page: Add and Cancel stay on screen, above the tab bar on phones.
    await page.screenshot({ path: test.info().outputPath(`${name}-5-add-in-view.png`), animations: 'disabled' });
    await page.route('**/api/restaurants/suggest?*', (route) =>
      route.fulfill({
        json: [
          // The one saved (Station East), another branch of it, and others, each with how far.
          { name: 'Gyoza Komachi', area: 'Hamamatsu Chuo', street: 'Kajimachi', kind: 'restaurant', category: 'eat', lat: 34.7032, lng: 137.737, meters: 240 },
          { name: 'Gyoza no Ōshō', area: 'Hamamatsu Chuo', street: 'Sunayama', kind: 'restaurant', category: 'eat', lat: 34.703, lng: 137.73, meters: 450 },
          { name: 'Gyoza Café', area: 'Hamamatsu Naka', street: '', kind: 'cafe', category: 'eat', lat: 34.71, lng: 137.72, meters: 1500 },
          { name: 'Gyoza Park', area: 'Hamamatsu Naka', street: '', kind: 'park', category: 'nature', lat: 34.72, lng: 137.72, meters: 2300 },
          { name: 'Gyoza Komachi', area: 'Hamamatsu Kita', street: 'Miyakoda', kind: 'restaurant', category: 'eat', lat: 34.8, lng: 137.7, meters: 11400 },
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
    // From Google, as the server answered "大仏" near Hamamatsu: nothing
    // near, so the well-known places (Text Search) come first, then
    // Autocomplete's far ones. Two are both named 大仏殿, and none has a
    // position yet.
    const google = (name, street, kind, category, meters, id) => ({ name, area: '', street, kind, category, lat: 0, lng: 0, meters, place_id: id, source: 'google' });
    await page.route('**/api/restaurants/suggest?*', (route) =>
      route.fulfill({
        json: [
          google('鎌倉大仏殿高徳院', '鎌倉市長谷４丁目２−２８', 'buddhist_temple', 'culture', 178000, 'ChIJkotokuin00'),
          google('牛久大仏', '牛久市久野町２０８３', 'buddhist_temple', 'culture', 267000, 'ChIJushiku0000'),
          google('大仏山公園 野球場', '三重県玉城町世古', 'athletic_field', 'fun', 101000, 'ChIJbaseball00'),
          google('大仏殿', '奈良県奈良市雑司町４０６−１', 'buddhist_temple', 'culture', 173000, 'ChIJdaibutsuden1'),
          google('大仏殿', '福井県勝山市片瀬５０−１−１', 'buddhist_temple', 'culture', 152000, 'ChIJdaibutsuden2'),
        ],
      }),
    );
    await page.goto('/app/#/add');
    await page.reload(); // a fresh form, not the one just filled in
    await page.getByLabel('Name', { exact: true }).fill('大仏');
    const far = page.getByRole('list', { name: 'Matching places' }).getByRole('listitem');
    await expect(far).toHaveCount(5);
    await expect(far.nth(0)).toContainText('鎌倉大仏殿高徳院');
    await expect(far.nth(0)).toContainText('178 km');
    await shot('5d-add-search-well-known');

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
          // Everyone around a working day: on the same shift, and on others
          // (the server shows those whose hours overlap).
          const together = /^\d/.test(code) ? [['タナカ ユイ', 'スズキ ケン'], ['サトウ ミホ']][i % 2].map((name) => ({ name, code })) : [];
          const people = /^\d/.test(code)
            ? [
                ...together,
                { name: 'ヤマダ ハナ', code: '9F' },
                { name: 'コバヤシ リク', code: '7B' },
                { name: 'サトウ ミオ', code: '17D' },
                { name: 'ヨル タロウ', code: '22F', day_offset: -1 },
                { name: 'アサ ミキ', code: '6D', day_offset: 1 },
              ]
            : [];
          return { date: `${month}-${String(i + 1).padStart(2, '0')}`, code, people };
        }),
      },
    });
    expect(res.status()).toBe(200);
    // Leaving the form with a name typed in asks first.
    await page.getByRole('link', { name: 'Shifts', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'Leave without saving?' })).toBeVisible();
    await page.screenshot({ path: test.info().outputPath(`${name}-5e-leave-unsaved.png`), animations: 'disabled' });
    await page.getByRole('dialog').getByRole('button', { name: 'Leave' }).click();
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
    await page.getByRole('link', { name: /^Edit \S+ \d{4}$/ }).click();
    await expect(page.getByRole('list', { name: 'Days' })).toBeVisible();
    await shot('7-shift-edit');
    // Who's there on a day (the 3rd, a 13F), to fix a name or a code.
    await page.getByRole('list', { name: 'Days' }).getByRole('listitem').nth(2).getByRole('button', { name: /^Who’s there on / }).click();
    await expect(page.getByRole('dialog', { name: /^Who’s there on / })).toBeVisible();
    await shot('7d-shift-people');
    await page.getByRole('dialog', { name: /^Who’s there on / }).getByRole('button', { name: 'Done' }).click();
    // This month's 締め申請通知 changed: only the last day.
    await page.getByRole('button', { name: 'Remove closing day 1' }).click();
    await expect(page.getByRole('button', { name: 'Use the usual days' })).toBeVisible();
    await shot('7a-shift-edit-closing');
    await page.goto('/app/#/shifts/calendar');
    // The month's change isn't saved: going asks first.
    await page.getByRole('dialog', { name: 'Leave without saving?' }).getByRole('button', { name: 'Leave' }).click();
    await expect(page.getByRole('link', { name: 'Open Google Calendar' })).toBeVisible();
    await shot('7a2-google-calendar');
    // Back on Shifts with the link on: a month with shifts, then an empty one.
    await page.goto('/app/#/shifts');
    await expect(page.getByRole('button', { name: 'Share via Google Calendar' })).toBeVisible();
    await shot('6d-shifts-calendar-on');
    await page.getByRole('link', { name: 'Next month' }).click();
    await page.getByRole('link', { name: 'Next month' }).click();
    await expect(page.getByText('No shifts for this month')).toBeVisible();
    await shot('6e-shifts-empty-month');

    // The ride alarm: first time, then with a stop chosen.
    await page.getByRole('link', { name: 'Ride', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'How it works' })).toBeVisible();
    await shot('7b-bus-setup');
    expect((await page.request.put('/api/bus/stop', { data: { name: '葵西小学校入口', lat: 34.76966, lng: 137.71207, alert_meters: 800 } })).status()).toBe(200);
    expect((await page.request.post('/api/bus/stops', { data: { name: '静岡駅', lat: 34.9717, lng: 138.3889, shifts: ['Early', 'Middle'], kind: 'train', alert_meters: 2000 } })).status()).toBe(201);
    await page.reload();
    await expect(page.getByRole('button', { name: 'Try it now' })).toBeVisible();
    await shot('7c-bus');
    await page.getByRole('link', { name: 'Settings', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
    await shot('7d-bus-settings');
    await page.getByRole('button', { name: 'Change 静岡駅' }).click();
    await expect(page.getByRole('button', { name: /^Buzz before/ })).toBeVisible();
    await shot('7e-bus-stop');
    await page.getByRole('link', { name: 'Settings', exact: true }).click();
    await page.getByRole('link', { name: 'Ride alarm', exact: true }).click();

    await page.getByRole('link', { name: 'Us', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'The two of you' })).toBeVisible();
    await shot('8-us');

    // The same screens in dark, picked on Us.
    await page.getByLabel('Appearance').selectOption('dark');
    await shot('9-us-dark');
    await page.getByRole('link', { name: 'Places' }).click();
    await expect(page.getByRole('heading', { name: 'Our places' })).toBeVisible();
    await shot('10-food-dark');
    await page.getByRole('link', { name: 'Ride', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Try it now' })).toBeVisible();
    await shot('11-bus-dark');
    // The calendar's shift colours in dark.
    await page.getByRole('link', { name: 'Shifts', exact: true }).click();
    await page.getByRole('radio', { name: 'Calendar' }).click();
    await shot('12-shifts-dark');

    // Nothing may overflow sideways.
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
}
