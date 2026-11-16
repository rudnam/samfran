const { test, expect, signUp } = require('./fixtures');

test('choose the bus stop from a Google Maps link', async ({ page }) => {
  await signUp(page);
  await page.getByRole('link', { name: 'Bus', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Where do you get off?' })).toBeVisible();
  // On the web, the alarm itself is in the Android app.
  await expect(page.getByRole('heading', { name: 'On the phone' })).toBeVisible();

  await page.getByLabel('Google Maps link to the stop').fill('https://www.google.com/maps/place/Kamijima+Station/@34.7108,137.7261,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d34.7111!4d137.7265');
  await page.getByRole('button', { name: 'Use link' }).click();
  await expect(page.getByText('Found it.')).toBeVisible();
  await expect(page.getByLabel('Stop name')).toHaveValue('Kamijima Station');
  await page.getByLabel('Buzz this far before the stop').selectOption('1000');
  await page.getByRole('button', { name: 'Save' }).click();

  await expect(page.getByText('Kamijima Station')).toBeVisible();
  await expect(page.getByText('Buzzes 1.0 km before the stop.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'See it on the map' })).toHaveAttribute('href', /query=34\.7111,137\.7265/);
  await page.screenshot({ path: test.info().outputPath('bus.png'), fullPage: true });

  await page.reload();
  await expect(page.getByText('Kamijima Station')).toBeVisible();
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
  await expect(page.getByRole('link', { name: 'See it on the map' })).toHaveAttribute('href', /query=34\.76966,137\.71207/);
});
