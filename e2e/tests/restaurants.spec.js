const { test, expect, signUp } = require('./fixtures');

test('add a place, rate it, log visits, edit and delete it', async ({ page }) => {
  await signUp(page);
  await expect(page.getByText('No places yet')).toBeVisible();

  // Been there: with a date and a rating.
  await page.getByRole('link', { name: 'Add a place' }).first().click();
  await page.getByLabel('Name', { exact: true }).fill('Taco Loco');
  await page.getByLabel('Area').fill('Station East');
  await page.getByLabel('Food').fill('Mexican');
  await page.getByRole('button', { name: '4 stars' }).click();
  await page.getByRole('button', { name: 'Add', exact: true }).click();

  await expect(page.getByRole('heading', { name: 'Taco Loco' })).toBeVisible();
  await expect(page.getByText('Station East · Mexican')).toBeVisible();
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
  await expect(page.getByText('Station East · Mexican')).toBeVisible();

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

  // Picking fills in the name, area and, from the kind of place, the food; the list goes away.
  await expect(list).toHaveCount(0);
  await expect(name).toHaveValue('すき家 浜松駅前店');
  await expect(page.getByLabel('Area')).toHaveValue('浜松市 中央区');
  await expect(page.getByLabel('Food')).toHaveValue('Fast food');
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
  // What they typed in Food stays when they pick a place.
  await page.getByLabel('Food').fill('Gyudon');
  await list.getByRole('button', { name: /すき家 葵町店/ }).click();
  await expect(page.getByLabel('Food')).toHaveValue('Gyudon');
  // Typed out in full, too.
  await name.fill('すき家 浜松駅前店');
  await expect(page.getByText('Already on your list:')).toBeVisible();
});

test('with Google: picking a suggestion fills in its area, food and its own Maps link', async ({ page }) => {
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
  await expect(page.getByLabel('Food')).toHaveValue('Chinese Restaurant');
  await expect(page.getByRole('link', { name: 'Check on the map' })).toHaveAttribute('href', 'https://maps.google.com/?cid=123');
  // Typing and picking were one Google session.
  expect(detailsSession).toMatch(/^[0-9a-f]{32}$/);
  expect(sessions.at(-1)).toBe(detailsSession);

  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('heading', { name: '石松 JR浜松駅店' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open in Maps' })).toHaveAttribute('href', 'https://maps.google.com/?cid=123');
});
