const { test, expect, signUp, standAt, pickCategory, pickOrder, photo, addPhoto } = require('./fixtures');

test('add a place, rate it, change when you went, edit and delete it', async ({ page }) => {
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
  // Been once: one visit, listed.
  await expect(page.getByText(/^Been · /)).toBeVisible();
  const visits = page.getByRole('list', { name: 'Visits' }).getByRole('listitem');
  await expect(visits).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Mark as been' })).toHaveCount(0);

  // Change when you went (the visit's Edit), then the rating.
  await page.getByRole('button', { name: /^Edit the visit on / }).click();
  await page.getByLabel('When did you go?').fill('2026-01-15');
  await page.getByLabel('Note (optional)').fill('Al pastor, the best so far');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(visits.first()).toContainText('Al pastor, the best so far');
  await expect(page.getByText(/^Been · Jan 15/)).toBeVisible();
  await expect(page.getByLabel('When did you go?')).toHaveCount(0);
  await page.getByRole('button', { name: '5 stars' }).click();
  await expect(page.getByRole('button', { name: '5 stars' })).toHaveAttribute('aria-pressed', 'true');

  // Edit keeps what isn't changed.
  await page.getByRole('link', { name: 'Edit' }).click();
  // Its way back is named after the place, as saved.
  await expect(page.getByRole('link', { name: 'Taco Loco' })).toHaveAttribute('href', /#\/restaurant\?id=/);
  await page.getByLabel('Notes').fill('Ask for the green salsa.');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Ask for the green salsa.')).toBeVisible();
  await expect(page.getByText('Eat & drink · Mexican · Station East')).toBeVisible();
  await expect(page.getByText(/^Been · Jan 15/)).toBeVisible();

  // Back on the list, with the rating and the date; one visit isn't counted.
  await page.getByRole('link', { name: 'Our places' }).click();
  const card = page.getByRole('list', { name: 'Been there' }).getByRole('listitem');
  await expect(card).toHaveCount(1);
  await expect(card).toContainText('★5');
  await expect(card).toContainText('Jan 15');
  await expect(card).not.toContainText('×');

  // Deleting the place asks first, in the app's own dialog.
  await card.getByRole('link').click();
  await page.getByRole('button', { name: 'Delete this place' }).click();
  const ask = page.getByRole('dialog', { name: 'Delete Taco Loco?' });
  await expect(ask).toContainText('Its ratings go with it.');
  await ask.getByRole('button', { name: 'Cancel' }).click();
  await expect(ask).toBeHidden();
  await expect(page.getByRole('heading', { name: 'Taco Loco' })).toBeVisible();
  await page.getByRole('button', { name: 'Delete this place' }).click();
  await ask.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByText('No places yet')).toBeVisible();
});

test('went again: visits newest first, each with its note; deleting the last one moves it back to want to go', async ({ page }) => {
  await signUp(page);
  const res = await page.request.post('/api/restaurants', { data: { name: 'Ramen Tsubaki', visit: { visited_on: '2026-10-01', note: 'The tantanmen, extra spicy' } } });
  const { id } = await res.json();
  // More places, so the list offers Search (it does from five).
  for (const [name, on] of [['Gyoza Komachi', '2026-10-05'], ['Hanabi Grill', '2026-09-01'], ['Blue Sky Café', '2026-08-01'], ['Taco Loco', '2026-07-01']]) {
    expect((await page.request.post('/api/restaurants', { data: { name, visit: { visited_on: on } } })).status()).toBe(201);
  }
  await page.goto(`/app/#/restaurant?id=${id}`);
  await expect(page.getByText(/^Been · Oct 1/)).toBeVisible();
  const visits = page.getByRole('list', { name: 'Visits' }).getByRole('listitem');
  await expect(visits).toHaveCount(1);
  await expect(visits.first()).toContainText('The tantanmen, extra spicy');
  // A visit's note stays on the visit, not in the place's notes.
  await expect(page.getByRole('heading', { name: 'Notes' })).toHaveCount(0);

  // Went again: asks when (today unless changed) and an optional note; Cancel leaves it.
  await page.getByRole('button', { name: 'Went again' }).click();
  await page.getByRole('button', { name: 'Cancel' }).click();
  await expect(visits).toHaveCount(1);
  await page.getByRole('button', { name: 'Went again' }).click();
  const today = await page.evaluate(() => new Date().toLocaleDateString('en-CA'));
  await expect(page.getByLabel('When did you go?')).toHaveValue(today);
  await page.getByLabel('When did you go?').fill('2026-11-02');
  await page.getByLabel('Note (optional)').fill('Shio this time');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(visits).toHaveCount(2);
  await expect(visits.first()).toContainText('Nov 2');
  await expect(visits.first()).toContainText('Shio this time');
  await expect(page.getByText('Been 2 times · last Nov 2')).toBeVisible();

  // The list: the newest date, how many times, and Search finds a visit's note.
  await page.getByRole('link', { name: 'Our places' }).click();
  const cards = page.getByRole('list', { name: 'Been there' }).getByRole('listitem');
  await expect(cards.first()).toContainText('Ramen Tsubaki');
  await expect(cards.first()).toContainText('Nov 2');
  await expect(cards.first()).toContainText('2×');
  await page.getByRole('button', { name: 'Search' }).click();
  await page.getByRole('searchbox', { name: 'Search' }).fill('tantan');
  await expect(cards).toHaveCount(1);
  await expect(cards).toContainText('Ramen Tsubaki');

  // Deleting a visit asks first; the last one puts the place back on Want to go.
  await cards.first().getByRole('link').click();
  await page.getByRole('button', { name: 'Edit the visit on Nov 2' }).click();
  await page.getByRole('button', { name: 'Delete this visit' }).click();
  let ask = page.getByRole('dialog', { name: 'Delete the visit on Nov 2?' });
  await expect(ask).not.toContainText('Want to go');
  await ask.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(visits).toHaveCount(1);
  await expect(page.getByText(/^Been · Oct 1/)).toBeVisible();
  await page.getByRole('button', { name: 'Edit the visit on Oct 1' }).click();
  await page.getByRole('button', { name: 'Delete this visit' }).click();
  ask = page.getByRole('dialog', { name: 'Delete the visit on Oct 1?' });
  await expect(ask).toContainText('Ramen Tsubaki goes back to Want to go.');
  await ask.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Mark as been' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Visits' })).toHaveCount(0);
});

test('photos: added to a visit as they were taken, shown in a strip, opened full size, and the newest is the place’s picture', async ({ page }) => {
  await signUp(page);
  const res = await page.request.post('/api/restaurants', { data: { name: 'Hanabi Grill', cuisine: 'Hamburg steak', visit: { visited_on: '2026-11-14' } } });
  const { id } = await res.json();
  await page.goto(`/app/#/restaurant?id=${id}`);
  const visit = page.getByRole('list', { name: 'Visits' }).getByRole('listitem').first();
  const strip = visit.getByLabel('Photos from Nov 14');
  await expect(strip.getByRole('button', { name: /^Photo / })).toHaveCount(0);

  // Two photos picked at once go up one after the other, untouched.
  const a = await photo(page, { width: 2400, height: 1800, hue: 20, name: 'steak.jpg' });
  const b = await photo(page, { width: 1200, height: 1600, hue: 140, name: 'garden.jpg' });
  await visit.locator('input[type=file]').setInputFiles([a, b]);
  await expect(strip.getByRole('button', { name: /^Photo / })).toHaveCount(2);
  await expect(strip.getByRole('progressbar')).toHaveCount(0);
  const place = await (await page.request.get(`/api/restaurants/${id}`)).json();
  const photos = place.visits[0].photos;
  expect(photos.map((p) => [p.width, p.height])).toEqual([[2400, 1800], [1200, 1600]]);
  const original = await page.request.get(photos[0].url);
  expect(Buffer.compare(await original.body(), a.buffer)).toBe(0); // byte for byte as it was taken
  expect(place.cover).toBe(photos[1].thumb_url);

  // A photo opens full size: the original; arrows move between them.
  await strip.getByRole('button', { name: 'Photo 1 of 2' }).click();
  const viewer = page.getByRole('dialog', { name: 'Photos from Nov 14' });
  await expect(viewer).toBeVisible();
  await expect(viewer.getByRole('img', { name: 'Photo 1 of 2' })).toHaveAttribute('src', photos[0].url);
  await expect(viewer.getByRole('button', { name: 'Previous photo' })).toHaveCount(0);
  await viewer.getByRole('button', { name: 'Next photo' }).click();
  await expect(viewer.getByRole('img', { name: 'Photo 2 of 2' })).toHaveAttribute('src', photos[1].url);
  await page.keyboard.press('ArrowLeft');
  await expect(viewer.getByRole('img', { name: 'Photo 1 of 2' })).toBeVisible();
  await expect(viewer.getByRole('link', { name: 'Download' })).toHaveAttribute('href', photos[0].url);

  // Deleting one asks first.
  await viewer.getByRole('button', { name: 'Delete' }).click();
  await page.getByRole('dialog', { name: 'Delete this photo?' }).getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(viewer.getByRole('img', { name: 'Photo 1 of 1' })).toHaveAttribute('src', photos[1].url);
  await page.keyboard.press('Escape');
  await expect(viewer).toBeHidden();
  await expect(strip.getByRole('button', { name: /^Photo / })).toHaveCount(1);

  // On the list, the place's newest photo is where its category's icon was.
  await page.getByRole('link', { name: 'Our places' }).click();
  const card = page.getByRole('list', { name: 'Been there' }).getByRole('listitem').first();
  await expect(card.locator('img.tile')).toHaveAttribute('src', photos[1].thumb_url);
  await expect(card.locator('img.tile')).toHaveJSProperty('complete', true);

  // A visit with photos says they go with it.
  await addPhoto(page, id, place.visits[0].id, { hue: 200 });
  await page.goto(`/app/#/restaurant?id=${id}`);
  await page.getByRole('button', { name: 'Edit the visit on Nov 14' }).click();
  await page.getByRole('button', { name: 'Delete this visit' }).click();
  await expect(page.getByRole('dialog', { name: 'Delete the visit on Nov 14?' })).toContainText('Its 2 photos go with it.');
});

test('a file that isn’t a picture is refused, with words saying why', async ({ page }) => {
  await signUp(page);
  const res = await page.request.post('/api/restaurants', { data: { name: 'Taco Loco', visit: { visited_on: '2026-11-14' } } });
  const { id } = await res.json();
  await page.goto(`/app/#/restaurant?id=${id}`);
  const visit = page.getByRole('list', { name: 'Visits' }).getByRole('listitem').first();
  await visit.locator('input[type=file]').setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('not a photo') });
  await expect(visit.getByText('That photo can’t be opened here. Choose a JPEG, PNG or WebP picture.')).toBeVisible();
  await expect(visit.getByRole('button', { name: /^Photo / })).toHaveCount(0);
});

test('want to go, then been', async ({ page }) => {
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
  await expect(page.getByText(/^Been · /)).toHaveCount(0);

  // Mark as been asks when, today unless changed; Cancel leaves it as it was.
  await page.getByRole('button', { name: 'Mark as been' }).click();
  await page.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.getByLabel('When did you go?')).toHaveCount(0);
  await page.getByRole('button', { name: 'Mark as been' }).click();
  const when = page.getByLabel('When did you go?');
  const today = await page.evaluate(() => new Date().toLocaleDateString('en-CA'));
  await expect(when).toHaveValue(today);
  await expect(page.getByRole('button', { name: 'Not been yet' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText(/^Been · /)).toBeVisible();
  await page.getByRole('link', { name: 'Our places' }).click();
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
  await page.getByRole('link', { name: 'Our places' }).click();
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

test('one name in two places: the saved one says so, the other can be added; near where you are first', async ({ page }) => {
  // At Hamamatsu Station, with location allowed: the search is asked from here, not from the bus stop.
  await standAt(page, 34.7038, 137.7349);
  let asked = null;
  await page.route('**/api/restaurants/suggest?*', (route) => {
    asked = new URL(route.request().url()).searchParams;
    route.fulfill({
      json: [
        { name: 'まるや', area: '浜松市 中央区', street: '鍛冶町', kind: 'restaurant', category: 'eat', lat: 34.7041, lng: 137.7352, meters: 40, source: 'openstreetmap' },
        { name: 'まるや', area: '静岡市 葵区', street: '紺屋町', kind: 'restaurant', category: 'eat', lat: 34.9715, lng: 138.3862, meters: 65400, source: 'openstreetmap' },
      ],
    });
  });
  await signUp(page);
  // Saved before, in Hamamatsu: a few metres from where this map puts it.
  const res = await page.request.post('/api/restaurants', { data: { name: 'まるや', area: '浜松市 中央区', lat: 34.7042, lng: 137.7353 } });
  expect(res.status()).toBe(201);
  const hamamatsu = (await res.json()).id;

  await page.getByRole('link', { name: 'Add a place' }).first().click();
  const name = page.getByLabel('Name', { exact: true });
  await name.fill('まるや');
  const list = page.getByRole('list', { name: 'Matching places' });
  await expect(list.getByRole('listitem')).toHaveCount(2);
  expect(Number(asked.get('lat'))).toBeCloseTo(34.7038, 3);
  expect(Number(asked.get('lng'))).toBeCloseTo(137.7349, 3);
  // The saved one opens it; the one in Shizuoka is a place to pick, with a word about the other.
  await expect(list.getByRole('link', { name: /まるや.*Already on your list/ })).toHaveAttribute('href', `#/restaurant?id=${hamamatsu}`);
  const shizuoka = list.getByRole('button', { name: /まるや/ });
  await expect(shizuoka).toHaveText(/紺屋町 · 静岡市 葵区.*You have one in 浜松市 中央区.*65 km/);
  await shizuoka.click();
  await expect(page.getByLabel('Area')).toHaveValue('静岡市 葵区');
  await expect(page.getByText('You have another one, in 浜松市 中央区:')).toBeVisible();
  await expect(page.getByText('Already on your list:')).toHaveCount(0);
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'まるや' })).toBeVisible();

  // Typed in by hand, not found, with another town: not a saved one either.
  await page.route('**/api/restaurants/suggest?*', (route) => route.fulfill({ json: [] }));
  await page.goto('/app/#/add');
  await name.fill('まるや');
  await page.getByLabel('Area').fill('豊橋市');
  await expect(page.getByText(/You have another one, in (浜松市 中央区|静岡市 葵区):/)).toBeVisible();
  // With no town, it might be: said as before.
  await page.getByLabel('Area').fill('');
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
            { name: '石松 JR浜松駅店', area: '', street: '静岡県浜松市中央区砂山町', kind: 'chinese_restaurant', lat: 0, lng: 0, place_id: 'ChIJishimatsu1', meters: 412, source: 'google' },
            { name: '石松餃子 浜北本店', area: '', street: '静岡県浜松市浜名区', kind: 'restaurant', lat: 0, lng: 0, place_id: 'ChIJishimatsu2', meters: 14800, source: 'google' },
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
  // How far each is; Google credited, not OpenStreetMap.
  await expect(list.getByRole('listitem')).toHaveText([/石松 JR浜松駅店.*410 m/, /石松餃子 浜北本店.*15 km/]);
  await expect(page.getByText('Places from Google Maps')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Places from OpenStreetMap' })).toHaveCount(0);
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
  await page.getByRole('link', { name: 'Our places' }).click();
  const want = page.getByRole('list', { name: 'Want to go' }).getByRole('listitem');
  await expect(want).toHaveCount(3);
  const menu = page.getByRole('button', { name: /^Category:/ });
  await expect(menu).toHaveText(/All places\s*3/);
  await menu.click();
  const options = page.getByRole('radiogroup', { name: 'Category' }).getByRole('radio');
  await expect(options).toHaveText([/All places\s*3/, /Eat & drink\s*1/, /Nature & parks\s*1/, /History & culture\s*1/]);
  await options.filter({ hasText: 'Nature & parks' }).click();
  await expect(options).toHaveCount(0); // the menu closes
  await expect(want).toHaveCount(1);
  await expect(want).toContainText('浜松城公園');
  await page.reload();
  await expect(menu).toHaveAccessibleName('Category: Nature & parks');
  await expect(want).toHaveCount(1);

  // A place's category can be changed; picked by hand, it stays when a place is picked from the search.
  await want.getByRole('link').click();
  await page.getByRole('link', { name: 'Edit' }).click();
  await expect(page.getByRole('radio', { name: 'Nature & parks' })).toHaveAttribute('aria-checked', 'true');
  await page.getByRole('radio', { name: 'History & culture' }).click();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('History & culture · Park · 浜松市 中央区')).toBeVisible();
  await page.getByRole('link', { name: 'Our places' }).click();
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
  await standAt(page, 34.7038, 137.7349);
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

  await pickOrder(page, 'Nearby');
  await expect(page.getByRole('button', { name: 'Order: Nearby' })).toBeVisible();
  await expect(want).toHaveText([/うなぎ藤田.*\d+ m/, /浜松城公園.*1\.\d km/, /中田島砂丘.*5\.\d km/, /Somewhere/]);
  // Remembered: opened again, still nearest first.
  await page.reload();
  await expect(want.first()).toContainText('うなぎ藤田');

  // The map: a pin per place with a position; tapping one shows its card, which opens it.
  await page.getByRole('button', { name: 'Map' }).click();
  const map = page.frameLocator('iframe[title="Map of the places"]');
  await expect(map.getByRole('button')).toHaveCount(3);
  await expect(page.getByText('1 not on the map yet', { exact: false })).toBeVisible();
  await map.getByRole('button', { name: '浜松城公園' }).click();
  const card = page.locator('.map-card');
  await expect(card).toContainText('浜松城公園');
  await expect(card).toContainText(/1\.\d km/);
  // The category filter applies to the map too.
  await pickCategory(page, /Eat & drink/);
  await expect(map.getByRole('button')).toHaveCount(1);
  await pickCategory(page, /All places/);
  await map.getByRole('button', { name: '浜松城公園' }).click();
  await card.click();
  await expect(page.getByRole('heading', { name: '浜松城公園' })).toBeVisible();
});

test('without a map key there is no map, and Nearby says why when location is off', async ({ page }) => {
  await standAt(page); // location turned down
  await signUp(page);
  expect((await page.request.post('/api/restaurants', { data: { name: 'Castle Park', category: 'nature' } })).status()).toBe(201);
  await page.goto('/app/#/want');
  await page.reload();
  await expect(page.getByRole('button', { name: 'Map' })).toHaveCount(0);
  await pickOrder(page, 'Nearby');
  await expect(page.getByText('Location is off for this site', { exact: false })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Want to go' }).getByRole('listitem')).toHaveCount(1);
});

test('order: newest, best rated or by name, and remembered', async ({ page }) => {
  await signUp(page);
  const today = new Date().toISOString().slice(0, 10);
  // Added in this order, so newest first is the reverse.
  for (const [name, stars] of [
    ['Gyoza Komachi', 3],
    ['Ramen Tsubaki', 5],
    ['Unrated Udon', 0],
    ['Bento Box', 4],
  ]) {
    const res = await page.request.post('/api/restaurants', { data: { name, category: 'eat', visit: { visited_on: today }, stars } });
    expect(res.status()).toBe(201);
  }
  await page.reload();
  const been = page.getByRole('list', { name: 'Been there' }).getByRole('listitem');
  await expect(been).toHaveText([/Bento Box/, /Unrated Udon/, /Ramen Tsubaki/, /Gyoza Komachi/]);
  await expect(page.getByRole('button', { name: 'Order: Newest' })).toBeVisible();

  // Best rated first; nobody's rated Unrated Udon, so it's last.
  await pickOrder(page, 'Top rated');
  await expect(page.getByRole('button', { name: 'Order: Top rated' })).toBeVisible();
  // The button looks the same whichever order; the menu ticks the current one.
  await expect(page.getByRole('button', { name: 'Order: Top rated' })).not.toHaveClass(/\bon\b/);
  await page.getByRole('button', { name: 'Order: Top rated' }).click();
  await expect(page.getByRole('radio', { name: 'Top rated', checked: true }).locator('svg')).toHaveCount(1);
  await expect(page.getByRole('radio', { name: 'Newest' }).locator('svg')).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath('order-menu.png') });
  await page.keyboard.press('Escape');
  await expect(been).toHaveText([/Ramen Tsubaki/, /Bento Box/, /Gyoza Komachi/, /Unrated Udon/]);

  // By name; remembered when the app is opened again.
  await pickOrder(page, 'A–Z');
  await expect(been).toHaveText([/Bento Box/, /Gyoza Komachi/, /Ramen Tsubaki/, /Unrated Udon/]);
  await page.reload();
  await expect(been).toHaveText([/Bento Box/, /Gyoza Komachi/, /Ramen Tsubaki/, /Unrated Udon/]);

  // The menu closes on Escape, and Newest puts it back.
  await page.getByRole('button', { name: /^Order:/ }).click();
  await expect(page.getByRole('radiogroup', { name: 'Order' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('radiogroup', { name: 'Order' })).toHaveCount(0);
  await pickOrder(page, 'Newest');
  await expect(been.first()).toContainText('Bento Box');
});

test('comments: once you have been, say how it was; only your own can be deleted', async ({ page }) => {
  await signUp(page);
  await page.getByRole('link', { name: 'Add a place' }).first().click();
  await page.getByRole('radio', { name: 'Want to go' }).check();
  await page.getByLabel('Name', { exact: true }).fill('Kaze Ramen');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Kaze Ramen' })).toBeVisible();
  // Not been yet: nothing to comment on.
  await expect(page.getByRole('heading', { name: 'Comments' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Mark as been' }).click();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('heading', { name: 'Comments' })).toBeVisible();
  await expect(page.getByText('No comments yet. How was it?')).toBeVisible();
  const post = page.getByRole('button', { name: 'Post' });
  await expect(post).toBeDisabled();
  await page.getByLabel('Add a comment').fill('The broth was perfect.\nGet the extra egg.');
  await post.click();
  const list = page.getByRole('list', { name: 'Comments' });
  await expect(list.getByRole('listitem')).toHaveCount(1);
  await expect(list).toContainText('You');
  await expect(list).toContainText('Get the extra egg.');
  await expect(page.getByLabel('Add a comment')).toHaveValue('');

  // Still there after a reload.
  await page.reload();
  await expect(list.getByRole('listitem')).toHaveCount(1);

  // Deleting asks first.
  await list.getByRole('button', { name: 'Delete your comment' }).click();
  await page.getByRole('dialog', { name: 'Delete your comment?' }).getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByText('No comments yet. How was it?')).toBeVisible();
});

test('leaving a place form with changes asks first; saving or leaving it untouched doesn’t', async ({ page }) => {
  await signUp(page);
  // Untouched: the tab just opens.
  await page.getByRole('link', { name: 'Add a place' }).filter({ visible: true }).click();
  await expect(page.getByRole('heading', { name: 'Add a place' })).toBeVisible();
  await page.getByRole('link', { name: 'Shifts', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  await expect(page).toHaveURL(/#\/shifts$/);

  // Typed in: a tab asks, and Cancel stays on the form with what was typed.
  await page.getByRole('link', { name: 'Places', exact: true }).click();
  await page.getByRole('link', { name: 'Add a place' }).filter({ visible: true }).click();
  await page.getByLabel('Name', { exact: true }).fill('Soba Kanda');
  await page.getByRole('link', { name: 'Shifts', exact: true }).click();
  const ask = page.getByRole('dialog', { name: 'Leave without saving?' });
  await expect(ask).toBeVisible();
  await ask.getByRole('button', { name: 'Cancel' }).click();
  await expect(page).toHaveURL(/#\/add/);
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Soba Kanda');

  // The browser's Back asks too.
  await page.goBack();
  await expect(ask).toBeVisible();
  await expect(page).toHaveURL(/#\/add/);
  await ask.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Soba Kanda');

  // Adding it goes straight to the place.
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Soba Kanda' })).toBeVisible();
  await expect(page.getByRole('dialog')).toBeHidden();

  // Editing: Back, then Leave, goes back to the place, unchanged.
  await page.getByRole('link', { name: 'Edit' }).click();
  await page.getByLabel('Notes').fill('Cold soba in summer.');
  await page.goBack();
  await ask.getByRole('button', { name: 'Leave' }).click();
  await expect(page.getByRole('heading', { name: 'Soba Kanda' })).toBeVisible();
  await expect(page).toHaveURL(/#\/restaurant\?id=/);
  await expect(page.getByText('Cold soba in summer.')).toHaveCount(0);
});
