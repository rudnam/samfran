const { test, expect, signUp } = require('./fixtures');

test('choose the first stop from a Google Maps link, then try a practice run', async ({ page }) => {
  await signUp(page);
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Ride alarm' })).toBeVisible();
  // First time: how it works, then where you get off.
  await expect(page.getByRole('heading', { name: 'How it works' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Where do you get off?' })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-setup.png'), fullPage: true });

  // A link is one of the other ways, folded away; then its name and kind are asked.
  await page.getByText('Can’t find it? Other ways').click();
  await page.getByLabel('Google Maps link to the stop').fill('https://www.google.com/maps/place/Kamijima+Station/@34.7108,137.7261,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d34.7111!4d137.7265');
  await page.getByRole('button', { name: 'Use link' }).click();
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Kamijima Station');
  await page.getByRole('radiogroup', { name: 'Bus or train' }).getByRole('radio', { name: 'Bus stop' }).click();
  await page.getByRole('button', { name: 'Use this' }).click();

  // Its page, filled in, with nothing saved until "Add stop".
  await expect(page.getByRole('heading', { name: 'Kamijima Station' })).toBeVisible();
  await expect(page.getByTitle('Kamijima Station on the map')).toHaveAttribute('src', /maps\?q=34\.7111%2C137\.7265&z=17/);
  await expect(page.getByRole('button', { name: /^Buzz before\s*800 m/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /^How it buzzes\s*Strong/ })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-new-stop.png'), fullPage: true });
  // A row opens just its choice; picking one closes it.
  await page.getByRole('button', { name: /^Buzz before/ }).click();
  const sheet = page.getByRole('dialog', { name: 'Buzz before Kamijima Station' });
  await expect(sheet.getByText('About one stop early.')).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-sheet.png'), fullPage: true });
  await sheet.getByRole('radio', { name: '1.0 km' }).click();
  await expect(sheet).toBeHidden();
  await expect(page.getByRole('button', { name: /^Buzz before\s*1\.0 km/ })).toBeVisible();
  await page.getByRole('button', { name: 'Add stop' }).click();

  // Ready: the stop, and on the web a practice run and the app.
  await expect(page.getByText('Buzzes 1.0 km before the stop. About two stops early.')).toBeVisible();
  await expect(page.getByRole('radio', { name: /Kamijima Station/ })).toHaveAttribute('aria-checked', 'true');
  await page.screenshot({ path: test.info().outputPath('bus.png'), fullPage: true });

  // The practice run: a pretend ride, then the buzz, then "Stop".
  await page.getByRole('button', { name: 'Try it now' }).click();
  await expect(page.getByText('Practice run')).toBeVisible();
  await expect(page.getByText(/to Kamijima Station$/)).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-practice.png'), fullPage: true });
  const buzz = page.getByRole('alertdialog', { name: 'Your stop is next' });
  await expect(buzz).toBeVisible({ timeout: 10_000 });
  await page.screenshot({ path: test.info().outputPath('bus-buzzing.png'), fullPage: true });
  await buzz.getByRole('button', { name: 'Stop', exact: true }).click();
  await expect(page.getByText('✓ That’s it')).toBeVisible();
  await page.getByRole('button', { name: 'Done' }).click();
  await expect(page.getByRole('button', { name: 'Try it now' })).toBeVisible();
});

test('a saved stop changes as it is changed, with its own buzz and sound', async ({ page }) => {
  await signUp(page);
  expect((await page.request.post('/api/bus/stops', { data: { name: 'Kamijima Station', lat: 34.7111, lng: 137.7265, alert_meters: 1000 } })).status()).toBe(201);
  await page.getByRole('link', { name: 'Ride', exact: true }).click();

  // A saved stop's page saves each change as it's made: how it buzzes there.
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Change Kamijima Station' }).click();
  await page.getByRole('button', { name: /^How it buzzes/ }).click();
  const how = page.getByRole('dialog', { name: 'How it buzzes at Kamijima Station' });
  await expect(how.getByRole('radio', { name: 'Strong', exact: true })).toHaveAttribute('aria-checked', 'true');
  await how.getByRole('radio', { name: 'Strongest' }).click();
  await expect(page.getByRole('button', { name: /^How it buzzes\s*Strongest/ })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-stop.png'), fullPage: true });
  // The sound if it isn't stopped: on, until switched off for the stop; and kept.
  const sound = page.getByRole('switch', { name: /Add sound if I don’t stop it/ });
  await expect(sound).toBeChecked();
  await sound.uncheck();
  await page.reload();
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.screenshot({ path: test.info().outputPath('bus-settings.png'), fullPage: true });
  await page.getByText('How it works').click();
  await expect(page.getByRole('link', { name: 'OpenStreetMap' })).toBeVisible();
  await page.getByRole('button', { name: 'Change Kamijima Station' }).click();
  await expect(sound).not.toBeChecked();
  await expect(page.getByRole('button', { name: /^How it buzzes\s*Strongest/ })).toBeVisible();
  await page.getByRole('button', { name: '‹ Settings' }).click();
  await page.getByRole('button', { name: '‹ Ride alarm' }).click();

  // A practice run then vibrates the stop's way, where the browser can.
  await page.evaluate(() => {
    window.vibrations = [];
    navigator.vibrate = (p) => (window.vibrations.push(p), true);
  });
  await page.getByRole('button', { name: 'Try it now' }).click();
  await expect(page.getByRole('alertdialog', { name: 'Your stop is next' })).toBeVisible({ timeout: 10_000 });
  expect((await page.evaluate(() => window.vibrations[0])).slice(0, 2)).toEqual([2000, 100]);
  await page.getByRole('button', { name: 'Stop', exact: true }).click();
  expect(await page.evaluate(() => window.vibrations.at(-1))).toBe(0);
});

test('find the stop by typing part of its name', async ({ page }) => {
  // The search is answered by Photon (OpenStreetMap); here, by this fake.
  await page.route('**/api/bus/search?*', (route) => {
    const q = new URL(route.request().url()).searchParams.get('q');
    route.fulfill({
      json: q.startsWith('葵西')
        ? [
            { name: '葵西小学校入口', area: '浜松市 中央区 磐田細江線', kind: 'bus', lat: 34.76966, lng: 137.71207 },
            { name: '葵西三丁目', area: '浜松市 中央区', kind: 'bus', lat: 34.772, lng: 137.715 },
            { name: '葵西小前', area: '浜松市 中央区', kind: 'train', lat: 34.774, lng: 137.717 },
          ]
        : [],
    });
  });
  await signUp(page);
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await page.getByLabel('Stop or station').fill('あおい');
  await expect(page.getByText('No stops by that name.')).toBeVisible();
  await page.getByLabel('Stop or station').fill('葵西小');
  const list = page.getByRole('list', { name: 'Matching stops' });
  await expect(list.getByRole('listitem')).toHaveCount(3);
  // Each says in words whether it's a bus stop or a station, in its own colour.
  await expect(list.getByText('Bus stop', { exact: true })).toHaveCount(2);
  await expect(list.getByText('Station', { exact: true })).toHaveCount(1);
  await expect(list.getByRole('button', { name: /葵西小前/ }).locator('.kind-tile.train')).toBeVisible();
  // OpenStreetMap is credited in How it works, not under every search.
  await expect(page.getByRole('link', { name: 'OpenStreetMap' })).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath('bus-search.png'), fullPage: true });
  await list.getByRole('button', { name: /葵西小学校入口/ }).click();

  await expect(page.getByRole('heading', { name: '葵西小学校入口' })).toBeVisible();
  await page.getByRole('button', { name: 'Add stop' }).click();
  await expect(page.getByTitle('葵西小学校入口 on the map')).toHaveAttribute('src', /maps\?q=34\.76966%2C137\.71207/);
  // The default: 800 m, about a stop early.
  await expect(page.getByText('Buzzes 800 m before the stop. About one stop early.')).toBeVisible();
});

test('keep a stop for each kind of shift, and pick another for a day', async ({ page }) => {
  await page.route('**/api/bus/search?*', (route) =>
    route.fulfill({ json: [{ name: '静岡駅', area: '静岡市 葵区', kind: 'train', lat: 34.9717, lng: 138.3889 }] }),
  );
  // Today: a late shift that ends in two hours.
  const end = new Date(Date.now() + 2 * 3600_000);
  await page.route('**/api/shifts', async (route) => {
    const month = await (await route.fetch()).json();
    const day = end.toISOString().slice(0, 10);
    month.days = [{ date: day, code: '13F', kind: 'work', label: 'Late', start: new Date(end - 9 * 3600_000).toISOString(), end: end.toISOString() }];
    route.fulfill({ json: month });
  });
  await signUp(page);
  expect((await page.request.post('/api/bus/stops', { data: { name: '葵西小学校入口', lat: 34.76966, lng: 137.71207 } })).status()).toBe(201);
  await page.getByRole('link', { name: 'Ride', exact: true }).click();
  await expect(page.getByRole('radio', { name: /葵西小学校入口/ })).toHaveAttribute('aria-checked', 'true');

  // A second stop, a station, for late shifts: added from Settings.
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: '+ Add a stop' }).click();
  await expect(page.getByRole('heading', { name: 'Add a stop' })).toBeVisible();
  await page.getByLabel('Stop or station').fill('静岡');
  await page.getByRole('list', { name: 'Matching stops' }).getByRole('button', { name: /静岡駅/ }).click();
  // A station: trains go further between stops, so it buzzes further ahead.
  await expect(page.getByRole('button', { name: /^Buzz before\s*2\.0 km/ })).toBeVisible();
  await page.getByRole('button', { name: /^Use after/ }).click();
  const shifts = page.getByRole('dialog', { name: 'Use 静岡駅 after' });
  await shifts.getByRole('button', { name: '遅番' }).click();
  await expect(shifts.getByRole('button', { name: '遅番' })).toHaveAttribute('aria-pressed', 'true');
  await shifts.getByRole('button', { name: 'Done' }).click();
  await expect(page.getByRole('button', { name: /^Use after\s*遅番/ })).toBeVisible();
  // A new stop doesn't ask when its shifts end, until switched on.
  await expect(page.getByRole('switch', { name: 'Ask when these end' })).not.toBeChecked();
  await page.screenshot({ path: test.info().outputPath('bus-stop-shifts.png'), fullPage: true });
  await page.getByRole('button', { name: 'Add stop' }).click();
  // Back in Settings, with the new one marked.
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Change 静岡駅' })).toContainText('Station · Just added');
  await page.screenshot({ path: test.info().outputPath('bus-stops-added.png'), fullPage: true });

  // Today's shift is late, so it's that stop, and the page says why.
  await page.reload();
  const picker = page.getByRole('radiogroup', { name: 'Get off at' });
  await expect(picker.getByRole('radio', { name: /静岡駅/ })).toHaveAttribute('aria-checked', 'true');
  // Each chip says whether it's a station or a bus stop.
  await expect(picker.getByRole('radio', { name: /静岡駅/ }).getByRole('img', { name: 'Station' })).toBeVisible();
  await expect(picker.getByRole('radio', { name: /葵西小学校入口/ }).getByRole('img', { name: 'Bus stop' })).toBeVisible();
  await expect(page.getByText(/^遅番 until \d\d:\d\d, so it’s 静岡駅\.$/)).toBeVisible();
  await expect(page.getByText('Buzzes 2.0 km before the stop. About one to two minutes early.')).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-stops.png'), fullPage: true });

  // Another for today: one tap, and no reason given.
  await picker.getByRole('radio', { name: /葵西小学校入口/ }).click();
  await expect(page.getByText(/so it’s/)).toHaveCount(0);

  // Late shifts move to the first stop: the other one loses them. And it asks when they end.
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Change 葵西小学校入口' }).click();
  await page.getByRole('button', { name: /^Use after/ }).click();
  const theirs = page.getByRole('dialog', { name: 'Use 葵西小学校入口 after' });
  await theirs.getByRole('button', { name: '遅番' }).click();
  await expect(theirs.getByText('静岡駅 won’t be used for them any more.')).toBeVisible();
  await theirs.getByRole('button', { name: 'Done' }).click();
  await page.getByRole('switch', { name: 'Ask when these end' }).check();
  await page.getByRole('button', { name: '‹ Settings' }).click();
  await expect(page.getByRole('button', { name: 'Change 葵西小学校入口' })).toContainText('After 遅番');
  await expect(page.getByRole('button', { name: 'Change 静岡駅' })).not.toContainText('After');
  await page.getByRole('button', { name: 'Change 葵西小学校入口' }).click();
  await expect(page.getByRole('switch', { name: 'Ask when these end' })).toBeChecked();

  // Moving a stop: its page's Where opens the search.
  await page.getByRole('button', { name: /^Where/ }).click();
  await page.getByLabel('Stop or station').fill('静岡');
  await page.getByRole('list', { name: 'Matching stops' }).getByRole('button', { name: /静岡駅/ }).click();
  await expect(page.getByRole('heading', { name: '静岡駅' })).toBeVisible();
  await page.getByRole('button', { name: '‹ Settings' }).click();

  // Removing a stop.
  await page.getByRole('button', { name: 'Change 静岡駅' }).first().click();
  await page.getByRole('button', { name: 'Remove this stop' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click();
  await expect(page.locator('.stop-list li')).toHaveCount(1);
  await page.getByRole('button', { name: '‹ Ride alarm' }).click();
  await expect(picker.getByRole('radio')).toHaveCount(1);
});
