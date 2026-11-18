const { test, expect, signUp } = require('./fixtures');

test('choose the bus stop from a Google Maps link, then try a practice run', async ({ page }) => {
  await signUp(page);
  await page.getByRole('link', { name: 'Bus', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Bus alarm' })).toBeVisible();
  // First time: how it works, then choosing the stop.
  await expect(page.getByRole('heading', { name: 'How it works' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Where do you get off?' })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-setup.png'), fullPage: true });

  // A link is one of the other ways, folded away.
  await page.getByText('Can’t find it? Other ways').click();
  await page.getByLabel('Google Maps link to the stop').fill('https://www.google.com/maps/place/Kamijima+Station/@34.7108,137.7261,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d34.7111!4d137.7265');
  await page.getByRole('button', { name: 'Use link' }).click();
  await expect(page.getByText('Found it.')).toBeVisible();
  await expect(page.getByLabel('Stop name')).toHaveValue('Kamijima Station');
  // A map of it: Google Maps, zoomed in on the stop.
  await expect(page.getByTitle('Kamijima Station on the map')).toHaveAttribute('src', /maps\?q=34\.7111%2C137\.7265&z=17/);
  const distance = page.getByRole('radiogroup', { name: 'Buzz this far before the stop' });
  await distance.getByRole('radio', { name: '1.0 km' }).click();
  await expect(page.getByText('About two stops early.')).toBeVisible();
  await page.getByRole('button', { name: 'Save' }).click();

  // Ready: the stop, and on the web a practice run and the app.
  await expect(page.getByText('Buzzes 1.0 km before the stop.')).toBeVisible();
  await expect(page.getByRole('radio', { name: /Kamijima Station/ })).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByRole('button', { name: 'Try it now' })).toBeVisible();
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
  await page.screenshot({ path: test.info().outputPath('bus-practice-done.png'), fullPage: true });
  await page.getByRole('button', { name: 'Done' }).click();
  await expect(page.getByRole('button', { name: 'Try it now' })).toBeVisible();

  // The distance is the stop's, changed with the stop, and kept.
  await page.getByRole('button', { name: 'Change Kamijima Station' }).click();
  await distance.getByRole('radio', { name: '600 m' }).click();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Buzzes 600 m before the stop.')).toBeVisible();
  await page.reload();
  await expect(page.getByText('Buzzes 600 m before the stop.')).toBeVisible();

  // How it buzzes: strong by default; changed with one tap, and kept.
  const how = page.getByRole('radiogroup', { name: 'How it buzzes' });
  await expect(how.getByRole('radio', { name: 'Strong', exact: true })).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByText('Longer buzzes, at full strength.')).toBeVisible();
  await how.getByRole('radio', { name: 'Strongest' }).click();
  await expect(page.getByText('Full strength, with hardly a pause.')).toBeVisible();
  await page.reload();
  await expect(how.getByRole('radio', { name: 'Strongest' })).toHaveAttribute('aria-checked', 'true');
  await page.screenshot({ path: test.info().outputPath('bus-buzz.png'), fullPage: true });

  // A practice run then vibrates that way, where the browser can.
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

test('find the bus stop by typing part of its name', async ({ page }) => {
  // The search is answered by Photon (OpenStreetMap); here, by this fake.
  await page.route('**/api/bus/search?*', (route) => {
    const q = new URL(route.request().url()).searchParams.get('q');
    route.fulfill({
      json: q.startsWith('葵西')
        ? [
            { name: '葵西小学校入口', area: '浜松市 中央区 磐田細江線', kind: 'bus', lat: 34.76966, lng: 137.71207 },
            { name: '葵西三丁目', area: '浜松市 中央区', kind: 'bus', lat: 34.772, lng: 137.715 },
          ]
        : [],
    });
  });
  await signUp(page);
  await page.getByRole('link', { name: 'Bus', exact: true }).click();
  await page.getByLabel('Stop name').fill('あおい');
  await expect(page.getByText('No stops by that name.')).toBeVisible();
  await page.getByLabel('Stop name').fill('葵西小');
  const list = page.getByRole('list', { name: 'Matching stops' });
  await expect(list.getByRole('listitem')).toHaveCount(2);
  await expect(page.getByRole('link', { name: 'Stops from OpenStreetMap' })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-search.png'), fullPage: true });
  await list.getByRole('button', { name: /葵西小学校入口/ }).click();

  await expect(list).toHaveCount(0);
  await expect(page.getByLabel('Stop name')).toHaveValue('葵西小学校入口');
  await expect(page.getByText('Found it.')).toBeVisible();
  await page.getByRole('button', { name: 'Save' }).click();
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
  await page.getByRole('link', { name: 'Bus', exact: true }).click();
  await expect(page.getByRole('radio', { name: /葵西小学校入口/ })).toHaveAttribute('aria-checked', 'true');

  // A second stop, for late shifts.
  await page.getByRole('button', { name: '+ Add a stop' }).first().click();
  await expect(page.getByRole('heading', { name: 'Add a stop' })).toBeVisible();
  await page.getByLabel('Stop name').fill('静岡');
  await page.getByRole('list', { name: 'Matching stops' }).getByRole('button', { name: /静岡駅/ }).click();
  const shifts = page.getByRole('group', { name: 'Use this stop after' });
  await shifts.getByRole('button', { name: '遅番' }).click();
  await expect(shifts.getByRole('button', { name: '遅番' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByText('After these shifts, the alarm picks 静岡駅 for you.')).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-stop-shifts.png'), fullPage: true });
  await page.getByRole('button', { name: 'Save' }).click();

  // Today's shift is late, so it's that stop, and the page says why.
  await page.reload();
  const picker = page.getByRole('radiogroup', { name: 'Get off at' });
  await expect(picker.getByRole('radio', { name: /静岡駅/ })).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByText(/^遅番 until \d\d:\d\d, so it’s 静岡駅\.$/)).toBeVisible();
  await expect(page.getByText('After 遅番 · 800 m')).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-stops.png'), fullPage: true });

  // Another for today: one tap, and no reason given.
  await picker.getByRole('radio', { name: /葵西小学校入口/ }).click();
  await expect(picker.getByRole('radio', { name: /葵西小学校入口/ })).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByText(/so it’s/)).toHaveCount(0);
  await expect(page.getByTitle('葵西小学校入口 on the map')).toBeVisible();

  // Late shifts move to the first stop: the other one loses them.
  await page.getByRole('button', { name: 'Change 葵西小学校入口' }).click();
  await shifts.getByRole('button', { name: '遅番' }).click();
  await expect(page.getByText('静岡駅 won’t be used for them any more.')).toBeVisible();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('After 遅番 · 800 m')).toBeVisible();
  await expect(page.getByText('When you pick it · 800 m')).toBeVisible();

  // No question at the end of its shifts, for one stop.
  await page.getByRole('button', { name: 'Change 静岡駅' }).click();
  const ask = page.getByRole('radiogroup', { name: 'Ask when a shift ends' });
  await expect(ask.getByRole('radio', { name: 'Ask', exact: true })).toHaveAttribute('aria-checked', 'true');
  await ask.getByRole('radio', { name: 'Don’t ask' }).click();
  await expect(page.getByText('Off: no question after shifts whose stop this is.')).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('bus-stop-no-question.png'), fullPage: true });
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('When you pick it · 800 m · No question')).toBeVisible();

  // Removing a stop.
  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: 'Change 静岡駅' }).click();
  await page.getByRole('button', { name: 'Remove this stop' }).click();
  await expect(picker.getByRole('radio')).toHaveCount(1);
  await expect(page.getByText(/^遅番 until \d\d:\d\d, so it’s 葵西小学校入口\.$/)).toBeVisible();
});
