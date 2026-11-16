// Light or dark: the app follows the device until someone picks one on
// Us → This device, and the pick is kept in this browser.
const { test, expect, signUp } = require('./fixtures');

const LIGHT_BG = 'rgb(253, 246, 238)';
const DARK_BG = 'rgb(23, 18, 14)';
const background = (page) => page.evaluate(() => getComputedStyle(document.body).backgroundColor);

test('Dark can be picked on a light device, and stays picked', async ({ page }) => {
  await signUp(page);
  expect(await background(page)).toBe(LIGHT_BG);
  await page.getByRole('link', { name: 'Us', exact: true }).click();
  await page.getByLabel('Appearance').selectOption('dark');
  expect(await background(page)).toBe(DARK_BG);
  await page.reload();
  await expect(page.getByLabel('Appearance')).toHaveValue('dark');
  expect(await background(page)).toBe(DARK_BG);
  await page.getByLabel('Appearance').selectOption('');
  expect(await background(page)).toBe(LIGHT_BG);
});

test.describe('on a dark device', () => {
  test.use({ colorScheme: 'dark' });

  test('the app is dark until Light is picked', async ({ page }) => {
    await signUp(page);
    expect(await background(page)).toBe(DARK_BG);
    await page.getByRole('link', { name: 'Us', exact: true }).click();
    await expect(page.getByLabel('Appearance')).toHaveValue('');
    await page.getByLabel('Appearance').selectOption('light');
    expect(await background(page)).toBe(LIGHT_BG);
    await page.reload();
    expect(await background(page)).toBe(LIGHT_BG);
  });
});
