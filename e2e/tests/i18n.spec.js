// Japanese: the app follows the device's language until someone picks one,
// and the introduction page shares that choice.
const { test, expect, signUp } = require('./fixtures');

test.describe('on a Japanese device', () => {
  test.use({ locale: 'ja-JP' });

  test('the app and its messages are in Japanese', async ({ page }) => {
    await page.goto('/app/');
    await expect(page.getByRole('heading', { name: 'ログイン' })).toBeVisible();
    await page.getByLabel('メールアドレス').fill('nobody@example.com');
    await page.getByLabel('パスワード').fill('wrong-password');
    await page.getByRole('button', { name: 'ログイン' }).click();
    // The server's English message, shown in Japanese.
    await expect(page.getByRole('alert')).toHaveText('メールアドレスまたはパスワードが正しくありません');
  });

  test('English can be picked, and the introduction keeps it', async ({ page }) => {
    await page.goto('/app/');
    await page.getByLabel('言語').selectOption('en');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await page.goto('/');
    await expect(page.getByRole('link', { name: 'Sign in' }).first()).toBeVisible();
  });
});

test('the introduction switches to Japanese and back', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: '日本語' }).click();
  await expect(page.getByRole('link', { name: 'ログイン' }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: 'Google Playで入手' })).toHaveAttribute('href', /play\.google\.com\/apps\/internaltest\//);
  // The app opens in the language picked here.
  await page.getByRole('link', { name: 'ログイン' }).first().click();
  await expect(page.getByRole('heading', { name: 'ログイン' })).toBeVisible();
  await page.goto('/');
  await page.getByRole('button', { name: 'English' }).click();
  await expect(page.getByRole('link', { name: 'Sign in' }).first()).toBeVisible();
});

test('the list of places is in Japanese too', async ({ page }) => {
  await signUp(page);
  await page.getByRole('link', { name: 'Us', exact: true }).click();
  await page.getByLabel('Language').selectOption('ja');
  await page.getByRole('link', { name: 'おでかけ' }).click();
  await expect(page.getByRole('heading', { name: 'ふたりのおでかけ' })).toBeVisible();
  await expect(page.getByText('まだ場所がありません')).toBeVisible();
});
