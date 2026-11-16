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
  await expect(page.locator('.cal-detail')).not.toContainText('締め申請通知');

  // 締め申請通知: the 15th and the last day are marked, and say so when tapped.
  const last = new Date(y, m, 0).getDate();
  await expect(cal.locator('.cal-flag')).toHaveCount(2);
  await expect(cal.getByRole('button', { name: /^14 \S+: Nothing entered$/ })).toBeVisible();
  await cal.getByRole('button', { name: /^15 \S+: Nothing entered, Hours due \(締め申請通知\)$/ }).click();
  await expect(page.locator('.cal-detail')).toContainText('締め申請通知');
  await expect(cal.getByRole('button', { name: new RegExp(`^${last} \\S+: .*締め申請通知\\)$`) })).toBeVisible();

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
  expect(await feed.text()).toContain(`DTSTART;VALUE=DATE:${month.replace('-', '')}15\r\nDTEND;VALUE=DATE:${month.replace('-', '')}16\r\nSUMMARY:締め申請通知`);

  // The 締め申請通知 days can be changed: the 10th and the 25th instead.
  await page.getByRole('combobox', { name: 'Closing day 1' }).selectOption('10');
  await page.getByRole('combobox', { name: 'Closing day 2' }).selectOption('25');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('status').getByText('Saved.')).toBeVisible();
  await page.getByRole('link', { name: '‹ Shifts' }).click();
  await page.getByRole('radio', { name: 'Calendar' }).click();
  await expect(cal.locator('.cal-flag')).toHaveCount(2);
  await expect(cal.getByRole('button', { name: /^10 \S+: Nothing entered, Hours due/ })).toBeVisible();
  await expect(cal.getByRole('button', { name: /^25 \S+: Nothing entered, Hours due/ })).toBeVisible();
  await expect(cal.getByRole('button', { name: /^15 \S+: Nothing entered$/ })).toBeVisible();

  // Or none at all, and the calendar has no dots.
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Remove closing day 2' }).click();
  await page.getByRole('button', { name: 'Remove closing day 1' }).click();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('status').getByText('Saved.')).toBeVisible();
  await page.getByRole('link', { name: '‹ Shifts' }).click();
  await expect(cal.locator('.cal-flag')).toHaveCount(0);
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
  const who = days.nth(0).getByRole('textbox');
  await expect(who).toHaveValue('タナカ ユイ, スズキ ケン');
  await days.nth(0).getByRole('combobox').fill('13F');
  await expect(who).toHaveValue('');
  await days.nth(0).getByRole('combobox').fill('22F');
  await expect(who).toHaveValue('タナカ ユイ, スズキ ケン');
  // A wrong read can be fixed by hand: change a name, add one.
  await who.fill('タナカ ユイ, サトウ ミキ');
  await page.getByRole('button', { name: /^Save / }).click();
  await page.getByRole('radio', { name: 'List' }).click();
  await expect(list.nth(0)).toContainText('With タナカ ユイ, サトウ ミキ');

  // The calendar feed says who too.
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Turn on the calendar link' }).click();
  const feed = await page.request.get(await page.getByLabel('Calendar link').inputValue());
  expect(await feed.text()).toContain('DESCRIPTION:一緒: タナカ ユイ、サトウ ミキ');
});

test('read a photo of the sheet: the page waits for the read, then opens the month to check', async ({ page }) => {
  await signUp(page);
  const month = thisMonth();
  await page.request.put('/api/shifts/settings', { data: { person_name: 'Sam', name_on_sheet: 'オトル サマンタ' } });

  // This server has no Claude key, so stand in for it: photos can be read,
  // and the read takes a few askings to finish (like the minute Claude takes).
  await page.route(/\/api\/shifts(\?.*)?$/, async (route) => {
    const res = await route.fetch();
    const body = await res.json();
    body.settings.reader = true;
    await route.fulfill({ response: res, json: body });
  });
  let started = 0;
  await page.route('**/api/shifts/reads', (route) => {
    started++;
    return route.fulfill({ status: 202, json: { id: 'r1' } });
  });
  let asked = 0;
  await page.route('**/api/shifts/reads/r1', async (route) => {
    if (++asked < 3) return route.fulfill({ status: 202, json: { status: 'reading' } });
    const settings = await (await page.request.get('/api/shifts/settings')).json();
    return route.fulfill({
      json: {
        month,
        settings: { ...settings, reader: true },
        days: [{ date: `${month}-01`, code: '22F', kind: 'work', label: '夜勤', with: ['タナカ ユエ'] }],
        name_as_read: 'オトル サマンタ',
        notes: '',
      },
    });
  });

  await page.getByRole('link', { name: 'Shifts', exact: true }).click();
  // A real (1×1 PNG) photo, so the page can shrink it.
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
  await page.getByLabel('Photo of the sheet').setInputFiles({ name: 'sheet.png', mimeType: 'image/png', buffer: png });
  await expect(page.getByRole('button', { name: 'Reading the sheet…' })).toBeDisabled();

  await expect(page.getByRole('heading', { name: 'Check the shifts' })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('status')).toContainText('オトル サマンタ');
  const days = page.getByRole('list', { name: 'Days' }).getByRole('listitem');
  await expect(days.nth(0).getByRole('combobox')).toHaveValue('22F');
  expect(started).toBe(1);
  expect(asked).toBe(3);

  // A misread name is fixed by hand, and the fix is sent so the next scan gets it right.
  const who = days.nth(0).getByRole('textbox');
  await expect(who).toHaveValue('タナカ ユエ');
  await who.fill('タナカ ユイ');
  const saving = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/api/shifts/month'));
  await page.getByRole('button', { name: /^Save / }).click();
  const sent = (await saving).postDataJSON();
  expect(sent.days[0].with).toEqual(['タナカ ユイ']);
  expect(sent.corrections).toEqual([{ from: 'タナカ ユエ', to: 'タナカ ユイ' }]);
});
