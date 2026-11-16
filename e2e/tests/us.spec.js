const { test, expect, signUp, uniqueEmail, PASSWORD } = require('./fixtures');

test('invite your partner, who then sees and rates the same places', async ({ page, browser }) => {
  await signUp(page);
  await page.getByRole('link', { name: 'Us', exact: true }).click();
  await page.getByRole('textbox', { name: 'Your name' }).fill('Fran');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Saved.')).toBeVisible();

  await page.getByRole('button', { name: 'Create invite link' }).click();
  const link = await page.getByLabel('Invite link').inputValue();
  expect(link).toMatch(/\/join\/[\w-]+$/);
  await page.reload();
  await expect(page.getByRole('list', { name: 'Recent changes' })).toContainText('You made an invite link');

  const res = await page.request.post('/api/restaurants', { data: { name: 'Gyoza Komachi', visit: { visited_on: '2026-11-01' }, stars: 4 } });
  expect(res.status()).toBe(201);

  // The partner taps the link, signs up with any email, and lands in the same space.
  const other = await browser.newContext();
  const sam = await other.newPage();
  await sam.goto(link);
  await expect(sam.getByRole('status')).toHaveText('Fran invited you to SamFran. Create your account to join.');
  await expect(sam.getByRole('heading', { name: 'Create an account' })).toBeVisible();
  await sam.getByLabel('Email').fill(uniqueEmail());
  await sam.getByLabel('Password').fill(PASSWORD);
  await sam.getByRole('button', { name: 'Create account' }).click();
  await expect(sam.getByRole('heading', { name: 'Our spots' })).toBeVisible();
  await sam.getByRole('link', { name: /Gyoza Komachi/ }).click();
  await expect(sam.getByText('Not rated yet')).toHaveCount(0);
  await expect(sam.locator('.rating-row').nth(1)).toContainText('Fran'); // Fran's rating, by name
  await sam.getByRole('button', { name: '5 stars' }).click();
  await expect(sam.getByRole('button', { name: '5 stars' })).toHaveAttribute('aria-pressed', 'true');
  await other.close();

  await page.getByRole('link', { name: 'Food' }).click();
  await expect(page.getByRole('list', { name: 'Been there' })).toContainText('F ★4');
  await page.reload();
  await page.getByRole('link', { name: 'Us', exact: true }).click();
  await expect(page.getByRole('list', { name: 'Members' }).getByRole('listitem')).toHaveCount(2);
});
