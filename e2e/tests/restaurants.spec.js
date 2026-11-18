const { test, expect, signUp } = require('./fixtures');

test('add a place, rate it, log visits, edit and delete it', async ({ page }) => {
  await signUp(page);
  await expect(page.getByText('No places yet')).toBeVisible();

  // Been there: with a date and a rating.
  await page.getByRole('link', { name: 'Add a place' }).first().click();
  await page.getByLabel('Name', { exact: true }).fill('Taco Loco');
  await page.getByLabel('Area').fill('Station East');
  await page.getByLabel('Kind').fill('Mexican');
  await page.getByRole('button', { name: '4 stars' }).click();
  await page.getByRole('button', { name: 'Add', exact: true }).click();

  await expect(page.getByRole('heading', { name: 'Taco Loco' })).toBeVisible();
  await expect(page.getByText('Eat & drink · Mexican · Station East')).toBeVisible();
  await expect(page.getByRole('button', { name: '4 stars' })).toHaveAttribute('aria-pressed', 'true');
  const visits = page.getByRole('list', { name: 'Visits' }).getByRole('listitem');
  await expect(visits).toHaveCount(1);

  // Another visit with a note, then change the rating.
  await page.getByLabel('Date').fill('2026-01-15');
  await page.getByLabel('Note about this visit').fill('Green salsa!');
  await page.getByRole('button', { name: 'Log visit' }).click();
  await expect(visits).toHaveCount(2);
  await expect(visits.last()).toContainText('Green salsa!'); // the older one is last
  await expect(page.getByLabel('Note about this visit')).toHaveValue('');
  await page.getByRole('button', { name: '5 stars' }).click();
  await expect(page.getByRole('button', { name: '5 stars' })).toHaveAttribute('aria-pressed', 'true');

  // Edit keeps what isn't changed.
  await page.getByRole('link', { name: 'Edit' }).click();
  await page.getByLabel('Notes').fill('Ask for the green salsa.');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Ask for the green salsa.')).toBeVisible();
  await expect(page.getByText('Eat & drink · Mexican · Station East')).toBeVisible();

  // Back on the list, with the rating and visit count.
  await page.getByRole('link', { name: 'Our spots' }).click();
  const card = page.getByRole('list', { name: 'Been there' }).getByRole('listitem');
  await expect(card).toHaveCount(1);
  await expect(card).toContainText('★5');
  await expect(card).toContainText('2 visits');

  // Remove a visit, then the place.
  await card.getByRole('link').click();
  // A removed visit can be brought back for a few seconds.
  await page.getByRole('button', { name: /^Delete the visit on/ }).first().click();
  await expect(visits).toHaveCount(1);
  await expect(page.getByText('1 visit', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(visits).toHaveCount(2);
  await page.getByRole('button', { name: /^Delete the visit on/ }).first().click();
  await expect(visits).toHaveCount(1);
  // Deleting the place asks first, in the app's own dialog.
  await page.getByRole('button', { name: 'Delete this place' }).click();
  const ask = page.getByRole('dialog', { name: 'Delete Taco Loco?' });
  await ask.getByRole('button', { name: 'Cancel' }).click();
  await expect(ask).toBeHidden();
  await expect(page.getByRole('heading', { name: 'Taco Loco' })).toBeVisible();
  await page.getByRole('button', { name: 'Delete this place' }).click();
  await ask.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByText('No places yet')).toBeVisible();
});

test('a removed visit is gone for good once Undo is no longer offered', async ({ page }) => {
  await signUp(page);
  const res = await page.request.post('/api/restaurants', { data: { name: 'Taco Loco', visit: { visited_on: '2026-10-01' } } });
  const { id } = await res.json();
  expect((await page.request.post(`/api/restaurants/${id}/visits`, { data: { visited_on: '2026-11-02' } })).status()).toBe(201);
  await page.goto(`/app/#/restaurant?id=${id}`);
  const visits = page.getByRole('list', { name: 'Visits' }).getByRole('listitem');
  await expect(visits).toHaveCount(2);
  await page.getByRole('button', { name: /^Delete the visit on/ }).first().click();
  await expect(page.getByRole('button', { name: 'Undo' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Undo' })).toBeHidden({ timeout: 10_000 });
  await page.reload();
  await expect(visits).toHaveCount(1);
  await expect(visits).toContainText('Oct');
});

test('search finds a place by what was written about a visit', async ({ page }) => {
  await signUp(page);
  for (const [name, note] of [
    ['Hanabi Grill', ''],
    ['Gyoza Komachi', ''],
    ['Ramen Tsubaki', 'The tantanmen, extra spicy'],
    ['Blue Sky Café', ''],
    ['Taco Loco', 'Green salsa'],
  ]) {
    const res = await page.request.post('/api/restaurants', { data: { name, visit: { visited_on: '2026-10-01', note } } });
    expect(res.status()).toBe(201);
  }
  await page.reload();
  const cards = page.getByRole('list', { name: 'Been there' }).getByRole('listitem');
  await expect(cards).toHaveCount(5);
  await page.getByRole('searchbox', { name: 'Search' }).fill('tantan');
  await expect(cards).toHaveCount(1);
  await expect(cards).toContainText('Ramen Tsubaki');
  await expect(cards).toContainText('The tantanmen, extra spicy');
  await page.getByRole('searchbox', { name: 'Search' }).fill('gyoza');
  await expect(cards).toHaveCount(1);
  await expect(cards).toContainText('Gyoza Komachi');
});

test('want to go, then went', async ({ page }) => {
  await signUp(page);
  await page.getByRole('link', { name: /Want to go/ }).click();
  await expect(page.getByText('Nothing on the list')).toBeVisible();
  await page.getByRole('link', { name: 'Add a place' }).first().click();
  await expect(page.getByRole('radio', { name: 'Want to go' })).toBeChecked();
  await page.getByLabel('Name', { exact: true }).fill('Blue Sky Café');
  await page.getByLabel('Google Maps link').fill('not a link');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  // The browser checks the link's form before the server sees it.
  await expect(page.getByRole('heading', { name: 'Blue Sky Café' })).toHaveCount(0);
  await page.getByLabel('Google Maps link').fill('https://maps.app.goo.gl/example');
  await page.getByRole('button', { name: 'Add', exact: true }).click();

  await expect(page.getByRole('heading', { name: 'Blue Sky Café' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open in Maps' })).toHaveAttribute('href', 'https://maps.app.goo.gl/example');
  await expect(page.getByText('Not yet')).toBeVisible();

  await page.getByRole('button', { name: 'We went!' }).click();
  await expect(page.getByRole('list', { name: 'Visits' }).getByRole('listitem')).toHaveCount(1);
  await page.getByRole('link', { name: 'Our spots' }).click();
  await expect(page.getByRole('list', { name: 'Been there' })).toContainText('Blue Sky Café');
});

test('a place shared from Google Maps opens Add a place, filled in', async ({ page }) => {
  await signUp(page);
  // What an installed web app gets from Android's share menu (share_target).
  const text = 'Taco Loco\nhttps://maps.app.goo.gl/AbC123';
  await page.goto(`/app/?text=${encodeURIComponent(text)}`);
  await expect(page.getByRole('heading', { name: 'Add a place' })).toBeVisible();
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Taco Loco');
  await expect(page.getByLabel('Google Maps link')).toHaveValue('https://maps.app.goo.gl/AbC123');
  await expect(page).not.toHaveURL(/text=/);
  await page.getByRole('radio', { name: 'Want to go' }).click();
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Taco Loco' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open in Maps' })).toHaveAttribute('href', 'https://maps.app.goo.gl/AbC123');
});

test('pasting "name and link" from Maps fills in both', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/add');
  await page.getByLabel('Google Maps link').focus();
  await page.evaluate(() => {
    const data = new DataTransfer();
    data.setData('text/plain', 'Gyoza Komachi\nhttps://maps.app.goo.gl/XyZ');
    document.activeElement.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  });
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Gyoza Komachi');
  await expect(page.getByLabel('Google Maps link')).toHaveValue('https://maps.app.goo.gl/XyZ');
});

test('find a place by typing its name: no Maps link needed', async ({ page }) => {
  // The search is answered by Photon (OpenStreetMap); here, by this fake.
  await page.route('**/api/restaurants/suggest?*', (route) => {
    const q = new URL(route.request().url()).searchParams.get('q');
    route.fulfill({
      json: q.startsWith('すき')
        ? [
            { name: 'すき家 浜松駅前店', area: '浜松市 中央区', street: '砂山町', kind: 'fast_food', lat: 34.703, lng: 137.734 },
            { name: 'すき家 葵町店', area: '浜松市 中央区', street: '', kind: 'fast_food', lat: 34.77, lng: 137.71 },
          ]
        : [],
    });
  });
  await signUp(page);
  await page.getByRole('link', { name: 'Add a place' }).first().click();
  const name = page.getByLabel('Name', { exact: true });
  await name.fill('zzz');
  await expect(page.getByText('Not found? Just type its name.')).toBeVisible();
  await name.fill('すき');
  const list = page.getByRole('list', { name: 'Matching places' });
  await expect(list.getByRole('listitem')).toHaveCount(2);
  await expect(page.getByRole('link', { name: 'Places from OpenStreetMap' })).toBeVisible();
  await list.getByRole('button', { name: /すき家 浜松駅前店/ }).click();

  // Picking fills in the name, area and, from the kind of place, its kind; the list goes away.
  await expect(list).toHaveCount(0);
  await expect(name).toHaveValue('すき家 浜松駅前店');
  await expect(page.getByLabel('Area')).toHaveValue('浜松市 中央区');
  await expect(page.getByLabel('Kind')).toHaveValue('Fast food');
  await expect(page.getByRole('link', { name: 'Check on the map' })).toBeVisible();
  await expect(page.getByLabel('Google Maps link')).toHaveValue('');
  await page.getByRole('button', { name: 'Add', exact: true }).click();

  // Open in Maps goes to that branch, not the chain.
  await expect(page.getByRole('heading', { name: 'すき家 浜松駅前店' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open in Maps' })).toHaveAttribute(
    'href',
    `https://www.google.com/maps/search/${encodeURIComponent('すき家 浜松駅前店')}/@34.703,137.734,17z`,
  );

  // Adding it again: the list says it's already saved, and links to it.
  await page.getByRole('link', { name: 'Our spots' }).click();
  await page.getByRole('link', { name: 'Add a place' }).first().click();
  await name.fill('すき家');
  await expect(list.getByRole('link', { name: /すき家 浜松駅前店.*Already on your list/ })).toBeVisible();
  // What they typed in Kind stays when they pick a place.
  await page.getByLabel('Kind').fill('Gyudon');
  await list.getByRole('button', { name: /すき家 葵町店/ }).click();
  await expect(page.getByLabel('Kind')).toHaveValue('Gyudon');
  // Typed out in full, too.
  await name.fill('すき家 浜松駅前店');
  await expect(page.getByText('Already on your list:')).toBeVisible();
});

test('with Google: picking a suggestion fills in its area, kind and its own Maps link', async ({ page }) => {
  // The server asks Google Places when it has a key; here, this fake answers.
  let sessions = [];
  await page.route('**/api/restaurants/suggest?*', (route) => {
    const u = new URL(route.request().url());
    sessions.push(u.searchParams.get('session'));
    route.fulfill({
      json: u.searchParams.get('q').startsWith('ishi')
        ? [
            { name: '石松 JR浜松駅店', area: '', street: '静岡県浜松市中央区砂山町', kind: 'chinese_restaurant', lat: 0, lng: 0, place_id: 'ChIJishimatsu1' },
            { name: '石松餃子 浜北本店', area: '', street: '静岡県浜松市浜名区', kind: 'restaurant', lat: 0, lng: 0, place_id: 'ChIJishimatsu2' },
          ]
        : [],
    });
  });
  let detailsSession = '';
  await page.route('**/api/restaurants/place/*', (route) => {
    const u = new URL(route.request().url());
    detailsSession = u.searchParams.get('session');
    expect(u.pathname).toBe('/api/restaurants/place/ChIJishimatsu1');
    route.fulfill({
      json: {
        name: '石松 JR浜松駅店', area: '浜松市 中央区', street: '砂山町', kind: 'chinese_restaurant', lat: 34.7036, lng: 137.7352,
        place_id: 'ChIJishimatsu1', food: 'Chinese Restaurant', map_url: 'https://maps.google.com/?cid=123',
      },
    });
  });
  await signUp(page);
  await page.getByRole('link', { name: 'Add a place' }).first().click();
  const name = page.getByLabel('Name', { exact: true });
  await name.fill('ishimatsu');
  const list = page.getByRole('list', { name: 'Matching places' });
  await expect(list.getByRole('listitem')).toHaveCount(2);
  await list.getByRole('button', { name: /石松 JR浜松駅店/ }).click();

  await expect(name).toHaveValue('石松 JR浜松駅店');
  await expect(page.getByLabel('Area')).toHaveValue('浜松市 中央区');
  await expect(page.getByLabel('Kind')).toHaveValue('Chinese Restaurant');
  await expect(page.getByRole('link', { name: 'Check on the map' })).toHaveAttribute('href', 'https://maps.google.com/?cid=123');
  // Typing and picking were one Google session.
  expect(detailsSession).toMatch(/^[0-9a-f]{32}$/);
  expect(sessions.at(-1)).toBe(detailsSession);

  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('heading', { name: '石松 JR浜松駅店' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open in Maps' })).toHaveAttribute('href', 'https://maps.google.com/?cid=123');
});

test('places of every kind: a category for each, and the list filtered by one', async ({ page }) => {
  // A park found by the search comes with its category.
  await page.route('**/api/restaurants/suggest?*', (route) =>
    route.fulfill({
      json: [{ name: '浜松城公園', area: '浜松市 中央区', street: '元城町', kind: 'park', category: 'nature', lat: 34.711, lng: 137.725 }],
    }),
  );
  await signUp(page);
  for (const [name, category] of [
    ['Taco Loco', undefined], // an older app's: no category, so eat
    ['Hilltop Shrine', 'culture'],
  ]) {
    expect((await page.request.post('/api/restaurants', { data: { name, category } })).status()).toBe(201);
  }
  await page.getByRole('link', { name: 'Add a place' }).first().click();
  await page.getByRole('radio', { name: 'Want to go' }).click();
  await page.getByLabel('Name', { exact: true }).fill('浜松城');
  await page.getByRole('list', { name: 'Matching places' }).getByRole('button', { name: /浜松城公園/ }).click();
  await expect(page.getByRole('radio', { name: 'Nature & parks' })).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByLabel('Kind')).toHaveValue('Park');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByText('Nature & parks · Park · 浜松市 中央区')).toBeVisible();

  // The list: all three, then one category's; the choice is remembered.
  await page.getByRole('link', { name: 'Our spots' }).click();
  const want = page.getByRole('list', { name: 'Want to go' }).getByRole('listitem');
  await expect(want).toHaveCount(3);
  const filter = page.getByRole('radiogroup', { name: 'Category' });
  await expect(filter.getByRole('radio')).toHaveText([/All\s*3/, /Eat\s*1/, /Nature\s*1/, /Culture\s*1/]);
  await filter.getByRole('radio', { name: /Nature/ }).click();
  await expect(want).toHaveCount(1);
  await expect(want).toContainText('浜松城公園');
  await page.reload();
  await expect(filter.getByRole('radio', { name: /Nature/ })).toHaveAttribute('aria-checked', 'true');
  await expect(want).toHaveCount(1);

  // A place's category can be changed; picked by hand, it stays when a place is picked from the search.
  await want.getByRole('link').click();
  await page.getByRole('link', { name: 'Edit' }).click();
  await expect(page.getByRole('radio', { name: 'Nature & parks' })).toHaveAttribute('aria-checked', 'true');
  await page.getByRole('radio', { name: 'History & culture' }).click();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('History & culture · Park · 浜松市 中央区')).toBeVisible();
  await page.getByRole('link', { name: 'Our spots' }).click();
  // Nothing left in Nature on this list: it says so, and offers the rest.
  await expect(page.getByText('Nothing in Nature here.')).toBeVisible();
  await page.getByRole('button', { name: 'Show all' }).click();
  await expect(want).toHaveCount(3);

  await page.route('**/api/restaurants/suggest?*', (route) =>
    route.fulfill({ json: [{ name: '浜名湖ガーデンパーク', area: '浜松市 中央区', street: '村櫛町', kind: 'park', category: 'nature', lat: 34.7, lng: 137.6 }] }),
  );
  await page.getByRole('link', { name: 'Add a place' }).first().click();
  await page.getByRole('radio', { name: 'Fun & activities' }).click();
  await page.getByLabel('Name', { exact: true }).fill('浜名湖');
  await page.getByRole('list', { name: 'Matching places' }).getByRole('button', { name: /浜名湖ガーデンパーク/ }).click();
  await expect(page.getByRole('radio', { name: 'Fun & activities' })).toHaveAttribute('aria-checked', 'true');
});

test('nearby: nearest first, with how far; and the places on a map', async ({ page }) => {
  // Standing at Hamamatsu Station.
  await page.context().grantPermissions(['geolocation']);
  await page.context().setGeolocation({ latitude: 34.7038, longitude: 137.7349 });
  // A map key, as the server has with GOOGLE_MAPS_BROWSER_KEY; Google itself is a fake (fixtures.js).
  await page.route('**/api/restaurants/map', (route) => route.fulfill({ json: { key: 'test-key' } }));
  await signUp(page);
  for (const [name, category, lat, lng] of [
    ['中田島砂丘', 'nature', 34.656, 137.731], // about 5 km south
    ['浜松城公園', 'nature', 34.7108, 137.7249], // about 1.2 km
    ['うなぎ藤田', 'eat', 34.7039, 137.7347], // next door
    ['Somewhere we haven’t found', 'other', undefined, undefined],
  ]) {
    expect((await page.request.post('/api/restaurants', { data: { name, category, lat, lng } })).status()).toBe(201);
  }
  await page.goto('/app/#/want');
  await page.reload(); // the places added above, not the list from signing up
  const want = page.getByRole('list', { name: 'Want to go' }).getByRole('listitem');
  await expect(want.first()).toContainText('Somewhere'); // newest first

  await page.getByRole('radio', { name: 'Nearby' }).click();
  await expect(want).toHaveText([/うなぎ藤田.*\d+ m/, /浜松城公園.*1\.\d km/, /中田島砂丘.*5\.\d km/, /Somewhere/]);
  // Remembered: opened again, still nearest first.
  await page.reload();
  await expect(want.first()).toContainText('うなぎ藤田');

  // The map: a pin per place with a position; tapping one shows its card, which opens it.
  await page.getByRole('radio', { name: 'Map' }).click();
  const map = page.frameLocator('iframe[title="Map of the places"]');
  await expect(map.getByRole('button')).toHaveCount(3);
  await expect(page.getByText('1 not on the map yet', { exact: false })).toBeVisible();
  await map.getByRole('button', { name: '浜松城公園' }).click();
  const card = page.locator('.map-card');
  await expect(card).toContainText('浜松城公園');
  await expect(card).toContainText(/1\.\d km/);
  // The category filter applies to the map too.
  await page.getByRole('radio', { name: /Eat/ }).click();
  await expect(map.getByRole('button')).toHaveCount(1);
  await page.getByRole('radio', { name: /All/ }).click();
  await map.getByRole('button', { name: '浜松城公園' }).click();
  await card.click();
  await expect(page.getByRole('heading', { name: '浜松城公園' })).toBeVisible();
});

test('without a map key there is no map, and Nearby says why when location is off', async ({ page }) => {
  await signUp(page);
  expect((await page.request.post('/api/restaurants', { data: { name: 'Castle Park', category: 'nature' } })).status()).toBe(201);
  await page.goto('/app/#/want');
  await page.reload();
  await expect(page.getByRole('radio', { name: 'Map' })).toHaveCount(0);
  await page.getByRole('radio', { name: 'Nearby' }).click(); // no permission granted
  await expect(page.getByText(/Location is off for this site|Couldn’t find where you are/)).toBeVisible();
  await expect(page.getByRole('list', { name: 'Want to go' }).getByRole('listitem')).toHaveCount(1);
});
