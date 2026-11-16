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
  await expect(days.nth(0)).toContainText('夜勤 · 22:00–7:00');
  await expect(days.nth(0)).not.toContainText('+1'); // ending next morning is obvious
  await days.nth(1).getByRole('combobox').fill('普');
  await days.nth(2).getByRole('combobox').fill('13z');
  await expect(days.nth(2)).toContainText('Unknown length');
  await days.nth(2).getByRole('combobox').fill('１３Ｆ'); // full-width works too
  await expect(days.nth(2)).toContainText('遅番 · 13:00–22:00');
  await page.getByRole('button', { name: /^Save / }).click();

  // The month as a calendar: a square for every day, and a day's times when it's tapped.
  const cal = page.getByRole('group', { name: 'Calendar' });
  const [y, m] = month.split('-').map(Number);
  await expect(cal.getByRole('button')).toHaveCount(new Date(y, m, 0).getDate());
  await expect(cal.locator('[aria-current="date"]')).toHaveCount(1); // today
  await cal.getByRole('button', { name: /^1 \S+: 夜勤 22:00–7:00$/ }).click();
  await expect(page.locator('.cal-detail')).toContainText('22F');
  await cal.getByRole('button', { name: /^2 \S+: 休み$/ }).click();
  await expect(page.locator('.cal-detail')).toContainText('普');
  await cal.getByRole('button', { name: /^9 \S+: Nothing entered$/ }).click();
  await expect(page.locator('.cal-detail')).toContainText('Nothing entered');

  // Or as a list, which this device then remembers.
  await page.getByRole('radio', { name: 'List' }).click();
  const list = page.getByRole('list', { name: 'Shifts' }).getByRole('listitem');
  await expect(list).toHaveCount(3);
  await expect(list.nth(0)).toContainText('夜勤');
  await expect(list.nth(0)).toContainText('22:00–7:00');
  await expect(list.nth(1)).toContainText('休み');
  await expect(list.nth(2)).toContainText('13F');
  await expect(page).toHaveURL(new RegExp(`month=${month}`));
  await page.reload();
  await expect(page.getByRole('radio', { name: 'List' })).toBeChecked();
  await expect(list).toHaveCount(3);

  // The calendar link.
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Turn on the calendar link' }).click();
  const link = page.getByLabel('Calendar link');
  await expect(link).toHaveValue(/\/api\/shifts\/feed\/[\w-]+\.ics$/);
  const feed = await page.request.get(await link.inputValue());
  expect(feed.status()).toBe(200);
  expect(await feed.text()).toContain('SUMMARY:夜勤');
});

test('see who else is on the same shift, and lose them when the shift changes', async ({ page }) => {
  await signUp(page);
  const month = thisMonth();
  await page.request.put('/api/shifts/settings', { data: { person_name: 'Sam', name_on_sheet: 'オトル サマンタ' } });
  const res = await page.request.put('/api/shifts/month', {
    data: {
      month,
      days: [
        { date: `${month}-01`, code: '22F', with: ['タナカ ユイ', 'スズキ ケン'] },
        { date: `${month}-02`, code: '普' },
      ],
    },
  });
  expect(res.status()).toBe(200);
  await page.getByRole('link', { name: 'Shifts', exact: true }).click();

  // On the calendar, a tapped day says who's on it.
  const cal = page.getByRole('group', { name: 'Calendar' });
  await cal.getByRole('button', { name: /^1 \S+: 夜勤/ }).click();
  await expect(page.locator('.cal-detail')).toContainText('With タナカ ユイ, スズキ ケン');

  // And in the list.
  await page.getByRole('radio', { name: 'List' }).click();
  const list = page.getByRole('list', { name: 'Shifts' }).getByRole('listitem');
  await expect(list.nth(0)).toContainText('With タナカ ユイ, スズキ ケン');
  await expect(list.nth(1)).not.toContainText('With');

  // Editing: still there while the code is the same; a different code is a different shift.
  await page.getByRole('link', { name: 'Edit by hand' }).click();
  const days = page.getByRole('list', { name: 'Days' }).getByRole('listitem');
  await expect(days.nth(0)).toContainText('With タナカ ユイ, スズキ ケン');
  await days.nth(0).getByRole('combobox').fill('13F');
  await expect(days.nth(0)).not.toContainText('With');
  await days.nth(0).getByRole('combobox').fill('22F');
  await expect(days.nth(0)).toContainText('With タナカ ユイ');
  await page.getByRole('button', { name: /^Save / }).click();
  await page.getByRole('radio', { name: 'List' }).click();
  await expect(list.nth(0)).toContainText('With タナカ ユイ, スズキ ケン');

  // The calendar feed says who too.
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Turn on the calendar link' }).click();
  const feed = await page.request.get(await page.getByLabel('Calendar link').inputValue());
  expect(await feed.text()).toContain('DESCRIPTION:一緒: タナカ ユイ、スズキ ケン');
});
