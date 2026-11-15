const { test, expect, signUp } = require('./fixtures');

function thisMonth() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

test('enter a month of shifts by hand, see them, and turn on the calendar link', async ({ page }) => {
  await signUp(page);
  await page.getByRole('link', { name: 'Shifts', exact: true }).click();
  await expect(page.getByText('No shifts for this month')).toBeVisible();

  // Whose shifts first.
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByLabel('Whose shifts').fill('Sam');
  await page.getByLabel('Name on the sheet').fill('オトル サマンタ');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('status').getByText('Saved.')).toBeVisible();
  await page.getByRole('link', { name: '‹ Shifts' }).click();
  await expect(page.getByRole('heading', { name: 'Sam’s shifts' })).toBeVisible();

  // By hand: the meaning shows as a code is typed.
  await page.getByRole('link', { name: 'Edit by hand' }).click();
  const month = thisMonth();
  const days = page.getByRole('list', { name: 'Days' }).getByRole('listitem');
  await days.nth(0).getByRole('combobox').fill('22F'); // the 1st
  await expect(days.nth(0)).toContainText('夜勤 · 22:00–6:00 (+1)');
  await days.nth(1).getByRole('combobox').fill('普');
  await days.nth(2).getByRole('combobox').fill('13z');
  await expect(days.nth(2)).toContainText('Unknown length');
  await days.nth(2).getByRole('combobox').fill('１３Ｆ'); // full-width works too
  await expect(days.nth(2)).toContainText('遅番 · 13:00–21:00');
  await page.getByRole('button', { name: /^Save / }).click();

  const list = page.getByRole('list', { name: 'Shifts' }).getByRole('listitem');
  await expect(list).toHaveCount(3);
  await expect(list.nth(0)).toContainText('夜勤');
  await expect(list.nth(0)).toContainText('22:00–6:00');
  await expect(list.nth(1)).toContainText('休み');
  await expect(list.nth(2)).toContainText('13F');
  await expect(page).toHaveURL(new RegExp(`month=${month}`));

  // The calendar link.
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Turn on the calendar link' }).click();
  const link = page.getByLabel('Calendar link');
  await expect(link).toHaveValue(/\/api\/shifts\/feed\/[\w-]+\.ics$/);
  const feed = await page.request.get(await link.inputValue());
  expect(feed.status()).toBe(200);
  expect(await feed.text()).toContain('SUMMARY:夜勤');
});
