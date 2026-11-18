const { test, expect, signUp } = require('./fixtures');

test('Claude asks to connect', async ({ page }) => {
  await signUp(page);

  // Claude registers itself and sends the browser to sign in.
  const callback = 'https://claude.example/api/mcp/auth_callback';
  const reg = await (await page.request.post('/oauth/register', { data: { client_name: 'Claude', redirect_uris: [callback] } })).json();
  const q = new URLSearchParams({
    response_type: 'code',
    client_id: reg.client_id,
    redirect_uri: callback,
    code_challenge: 'x'.repeat(43),
    code_challenge_method: 'S256',
    state: 'abc',
  });
  await page.goto(`/oauth/authorize?${q}`);
  await expect(page.getByRole('heading', { name: 'Connect Claude' })).toBeVisible();
  await expect(page.getByText('see and save the shifts')).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('connect.png'), fullPage: true });

  // Allowing sends the browser back to Claude with a code.
  const back = page.waitForRequest((r) => r.url().startsWith(callback));
  await page.route(`${callback}**`, (route) => route.fulfill({ status: 200, body: 'ok' }));
  await page.getByRole('button', { name: 'Allow' }).click();
  const url = new URL((await back).url());
  expect(url.searchParams.get('state')).toBe('abc');
  expect(url.searchParams.get('code')).toMatch(/^smcpa_/);
});
