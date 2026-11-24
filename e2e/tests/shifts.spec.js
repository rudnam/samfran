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
  await page.locator('a.back').click();
  await expect(page.getByRole('heading', { name: 'Sam’s shifts' })).toBeVisible();

  // An empty month: read a photo or enter it by hand; nothing to edit yet.
  await expect(page.getByRole('link', { name: /^Edit / })).toHaveCount(0);
  // By hand: the meaning shows as a code is typed.
  await page.getByRole('link', { name: 'Enter by hand' }).click();
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
  // A month with shifts: it's edited, by its name.
  await expect(page.getByRole('link', { name: /^Edit \S+ \d{4}$/ })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Enter by hand' })).toHaveCount(0);

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

  // The calendar link, from the Shifts page, with the way into Google Calendar.
  await page.getByRole('link', { name: 'Add to Google Calendar' }).click();
  await expect(page.getByRole('heading', { name: 'Add to Google Calendar' })).toBeVisible();
  // The link is on as soon as the page opens; it can't be turned off or replaced here.
  await expect(page.getByRole('button', { name: 'Turn off' })).toHaveCount(0);
  const link = page.getByLabel('Calendar link');
  await expect(link).toHaveValue(/\/api\/shifts\/feed\/[\w-]+\.ics$/);
  const webcal = (await link.inputValue()).replace(/^https?:/, 'webcal:');
  await expect(page.getByRole('link', { name: 'Open Google Calendar' })).toHaveAttribute(
    'href',
    `https://calendar.google.com/calendar/r?cid=${encodeURIComponent(webcal)}`,
  );
  await expect(page.getByText('Turn on Sync for “Sam’s shifts”')).toBeVisible();
  // On a phone, the website as a desktop page (the app can't add a calendar from a link).
  await expect(page.getByText('On a phone: the browser’s ⋮ menu › Desktop site.')).toBeVisible();
  const feed = await page.request.get(await link.inputValue());
  expect(feed.status()).toBe(200);
  expect(await feed.text()).toContain('SUMMARY:夜勤');
  expect(await feed.text()).toContain(`DTSTART;VALUE=DATE:${month.replace('-', '')}15\r\nDTEND;VALUE=DATE:${month.replace('-', '')}16\r\nSUMMARY:締め申請通知`);
  await page.locator('a.back').click();
  // Once it's on, the Shifts page has a quiet line that copies the Google Calendar address.
  // The tests' plain-http address has no clipboard (it needs https), so catch what's written instead.
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async (text) => void (window.copiedText = text) },
    });
  });
  await page.getByRole('button', { name: 'Share via Google Calendar' }).click();
  await expect(page.getByRole('button', { name: 'Copied' })).toBeVisible();
  expect(await page.evaluate(() => window.copiedText)).toBe(
    `https://calendar.google.com/calendar/r?cid=${encodeURIComponent(webcal)}`,
  );
  // Or the plain link, for any other calendar app.
  await page.getByRole('button', { name: 'Other calendars' }).click();
  await expect(page.getByRole('button', { name: 'Share via Google Calendar' })).toBeVisible();
  expect(await page.evaluate(() => window.copiedText)).toMatch(/^https?:\/\/[^/]+\/api\/shifts\/feed\/[\w-]+\.ics$/);
  await expect(page.getByRole('link', { name: 'Add to Google Calendar' })).toHaveCount(0);

  // The 締め申請通知 days can be changed: the 10th and the 25th instead.
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('combobox', { name: 'Closing day 1' }).selectOption('10');
  await page.getByRole('combobox', { name: 'Closing day 2' }).selectOption('25');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('status').getByText('Saved.')).toBeVisible();
  await page.locator('a.back').click();
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
  await page.locator('a.back').click();
  await expect(cal.locator('.cal-flag')).toHaveCount(0);
});

test('change the 締め申請通知 days for one month, next to its shifts', async ({ page }) => {
  await signUp(page);
  const month = thisMonth();
  const [y, m] = month.split('-').map(Number);
  const last = new Date(y, m, 0).getDate();
  await page.request.put('/api/shifts/settings', { data: { person_name: 'Sam', name_on_sheet: 'オトル サマンタ' } });
  await page.getByRole('link', { name: 'Shifts', exact: true }).click();
  await page.getByRole('link', { name: 'Enter by hand' }).click();

  // The usual days (the 15th and the last day) to start with.
  const box = page.getByRole('group', { name: 'Hours due (締め申請通知)' });
  await expect(box.getByRole('combobox', { name: 'Closing day 1' })).toHaveValue('15');
  await expect(box.getByRole('combobox', { name: 'Closing day 2' })).toHaveValue(String(last));
  await expect(box).toContainText('The usual days');
  await expect(box.getByRole('button', { name: 'Use the usual days' })).toHaveCount(0);

  // This month only: the 20th instead of the 15th, saved with a shift.
  await page.getByRole('list', { name: 'Days' }).getByRole('listitem').nth(0).getByRole('combobox').fill('13F');
  await box.getByRole('combobox', { name: 'Closing day 1' }).selectOption('20');
  await expect(box).toContainText('Only for');
  await page.getByRole('button', { name: /^Save / }).click();
  const cal = page.getByRole('group', { name: 'Calendar' });
  await expect(cal.locator('.cal-flag')).toHaveCount(2);
  await expect(cal.getByRole('button', { name: /^20 \S+: Nothing entered, Hours due/ })).toBeVisible();
  await expect(cal.getByRole('button', { name: /^15 \S+: Nothing entered$/ })).toBeVisible();

  // Next month keeps the usual days.
  await page.getByRole('link', { name: 'Next month' }).click();
  await expect(page).toHaveURL(/month=/);
  const next = m === 12 ? `${y + 1}-01` : `${y}-${String(m + 1).padStart(2, '0')}`;
  const nextMonth = await (await page.request.get(`/api/shifts?month=${next}`)).json();
  expect(nextMonth.closing_days[0]).toBe(15);
  expect(nextMonth.closing_custom).toBe(false);

  // The calendar link has this month's 20th, not its 15th.
  const settings = await (await page.request.post('/api/shifts/feed')).json();
  const feed = await (await page.request.get(settings.feed_path)).text();
  const ymd = month.replace('-', '');
  expect(feed).toContain(`DTSTART;VALUE=DATE:${ymd}20\r\nDTEND;VALUE=DATE:${ymd}21\r\nSUMMARY:締め申請通知`);
  expect(feed).not.toContain(`DTSTART;VALUE=DATE:${ymd}15\r\nDTEND;VALUE=DATE:${ymd}16\r\nSUMMARY:締め申請通知`);

  // Back to the usual days.
  await page.getByRole('link', { name: 'Previous month' }).click();
  await page.getByRole('link', { name: /^Edit \S+ \d{4}$/ }).click();
  await expect(box).toContainText('Only for');
  await box.getByRole('button', { name: 'Use the usual days' }).click();
  await expect(box.getByRole('combobox', { name: 'Closing day 1' })).toHaveValue('15');
  await page.getByRole('button', { name: /^Save / }).click();
  await expect(cal.getByRole('button', { name: /^15 \S+: Nothing entered, Hours due/ })).toBeVisible();
  expect((await (await page.request.get(`/api/shifts?month=${month}`)).json()).closing_custom).toBe(false);
});

test('see who else is on the same shift, fix them, and keep them when the shift changes', async ({ page }) => {
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

  // Nobody's shown until the people button is pressed; then a tapped day says who's on it.
  const cal = page.getByRole('group', { name: 'Calendar' });
  await cal.getByRole('button', { name: /^1 \S+: 夜勤/ }).click();
  await expect(page.locator('.cal-detail')).not.toContainText('タナカ');
  await page.getByRole('button', { name: 'Everyone there' }).click();
  await expect(page.locator('.cal-detail')).toContainText('タナカ ユイ');
  await expect(page.locator('.cal-detail')).toContainText('スズキ ケン');

  // And in the list.
  await page.getByRole('radio', { name: 'List' }).click();
  const list = page.getByRole('list', { name: 'Shifts' }).getByRole('listitem');
  await expect(list.nth(0)).toContainText('タナカ ユイ');
  await expect(list.nth(0)).toContainText('スズキ ケン');
  await expect(list.nth(1)).not.toContainText('タナカ');

  // Editing: each day is one line, with a count of who's there that opens them.
  await page.getByRole('link', { name: /^Edit \S+ \d{4}$/ }).click();
  const days = page.getByRole('list', { name: 'Days' }).getByRole('listitem');
  await expect(days.nth(0)).not.toContainText('タナカ');
  const count = days.nth(0).getByRole('button', { name: /^Who’s there on / });
  await expect(count).toHaveText('2');
  // A different code is a different shift: they aren't there during 13:00–22:00,
  // but nobody is lost, and going back brings them back.
  await days.nth(0).getByRole('combobox').fill('13F');
  await expect(count).toHaveText('+');
  await days.nth(0).getByRole('combobox').fill('22F');
  await expect(count).toHaveText('2');

  // A wrong read can be fixed by hand: change a name, add someone on another shift.
  await count.click();
  const sheet = page.getByRole('dialog', { name: /^Who’s there on / });
  await expect(sheet.getByLabel('Name of person 1')).toHaveValue('タナカ ユイ');
  await sheet.getByLabel('Name of person 2').fill('サトウ ミキ');
  await sheet.getByRole('button', { name: '+ Add someone' }).click();
  await sheet.getByLabel('Name of person 3').fill('ヤマダ ハナ');
  await sheet.getByLabel('Code of ヤマダ ハナ').fill('17D'); // 17:00–1:00: there as the night starts
  await expect(sheet.getByRole('list', { name: 'Everyone that day' }).getByRole('listitem').nth(2)).toContainText('17:00–1:00');
  // Just their hours: no note of which day their cell is on.
  await expect(sheet).not.toContainText('same day');
  await expect(sheet).not.toContainText('day before');
  await sheet.getByRole('button', { name: 'Done' }).click();
  await expect(sheet).toBeHidden();
  await expect(count).toHaveText('3');
  await page.getByRole('button', { name: /^Save / }).click();
  await page.getByRole('radio', { name: 'List' }).click();
  await page.getByRole('button', { name: 'Everyone there' }).click();
  await expect(list.nth(0)).toContainText('サトウ ミキ');
  await expect(list.nth(0)).toContainText('ヤマダ ハナ');
  await expect(list.nth(0)).not.toContainText('スズキ');

  // The calendar feed leaves out who: it can be shared.
  await page.getByRole('link', { name: 'Add to Google Calendar' }).click();
  const feed = await page.request.get(await page.getByLabel('Calendar link').inputValue());
  const ics = await feed.text();
  expect(ics).toContain('SUMMARY:');
  expect(ics).not.toContain('タナカ');
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
  let letGo = false;
  await page.route('**/api/shifts/reads/r1', async (route) => {
    ++asked;
    if (asked === 1) return route.fulfill({ status: 202, json: { status: 'reading', days_read: 0, days_in_month: 0 } });
    if (!letGo) return route.fulfill({ status: 202, json: { status: 'reading', name_as_read: 'オトル サマンタ', days_read: 12, days_in_month: 30 } });
    const settings = await (await page.request.get('/api/shifts/settings')).json();
    return route.fulfill({
      json: {
        month,
        settings: { ...settings, reader: true },
        days: [{ date: `${month}-01`, code: '22F', kind: 'work', label: '夜勤', with: ['タナカ ユエ'], people: [{ name: 'タナカ ユエ', code: '22F' }] }],
        name_as_read: 'オトル サマンタ',
        notes: '',
      },
    });
  });

  await page.getByRole('link', { name: 'Shifts', exact: true }).click();
  // A real (1×1 PNG) photo, so the page can shrink it.
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
  await page.getByLabel('Photo of the sheet').setInputFiles({ name: 'sheet.png', mimeType: 'image/png', buffer: png });
  // The progress takes the read button's place until it's done.
  await expect(page.getByRole('button', { name: 'Read a photo of the sheet' })).toHaveCount(0);
  // How far it's got: the photo's sent, the row found, and the days as they're read.
  const progress = page.getByRole('status', { name: 'Reading the sheet…' });
  await expect(progress).toContainText('Found オトル サマンタ on the sheet');
  await expect(progress).toContainText('Reading the days: 12 of 30');
  await expect(progress).not.toContainText('isn’t kept');
  await expect(progress.locator('.day-progress span.on')).toHaveCount(12);
  await page.screenshot({ path: test.info().outputPath('reading-progress.png'), animations: 'disabled' });
  letGo = true;

  await expect(page.getByRole('heading', { name: 'Check the shifts' })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('status')).toContainText('オトル サマンタ');
  const days = page.getByRole('list', { name: 'Days' }).getByRole('listitem');
  await expect(days.nth(0).getByRole('combobox')).toHaveValue('22F');
  expect(started).toBe(1);

  // A misread name is fixed by hand, and the fix is sent so the next scan gets it right.
  await days.nth(0).getByRole('button', { name: /^Who’s there on / }).click();
  const sheet = page.getByRole('dialog', { name: /^Who’s there on / });
  const who = sheet.getByLabel('Name of person 1');
  await expect(who).toHaveValue('タナカ ユエ');
  await who.fill('タナカ ユイ');
  await sheet.getByRole('button', { name: 'Done' }).click();
  const saving = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/api/shifts/month'));
  await page.getByRole('button', { name: /^Save / }).click();
  const sent = (await saving).postDataJSON();
  expect(sent.days[0].people).toEqual([{ name: 'タナカ ユイ', code: '22F', day_offset: 0 }]);
  expect(sent.corrections).toEqual([{ from: 'タナカ ユエ', to: 'タナカ ユイ' }]);
  // Saving opens the month, which loads it through the /api/shifts stand-in
  // above. Wait for the month, then for any stand-in still running (the next
  // month's load), or the test ends while one reads a response it's disposing.
  await expect(page.getByRole('group', { name: 'Calendar' }).getByRole('button', { name: /^1 \S+: 夜勤/ })).toBeVisible();
  await page.unrouteAll({ behavior: 'wait' });
});

test('see everyone there during a shift, not only those on the same one', async ({ page }) => {
  await signUp(page);
  const month = thisMonth();
  await page.request.put('/api/shifts/settings', { data: { person_name: 'Sam', name_on_sheet: 'オトル サマンタ' } });
  const others = [
    { name: 'ヤマダ ハナ', code: '9F' }, // 9–18: there
    { name: 'コバヤシ リク', code: '7B' }, // 7–12: gone as Sam starts
    { name: 'ヨル タロウ', code: '22F', day_offset: -1 }, // the night before, over by 7
  ];
  const res = await page.request.put('/api/shifts/month', {
    data: {
      month,
      days: [
        { date: `${month}-01`, code: '13F', with: ['タナカ ユイ'], others },
        { date: `${month}-02`, code: '6D', others: [{ name: 'ヨル タロウ', code: '22F', day_offset: -1 }] }, // 6–12: the night before runs into it
      ],
    },
  });
  expect(res.status()).toBe(200);
  await page.getByRole('link', { name: 'Shifts', exact: true }).click();
  const cal = page.getByRole('group', { name: 'Calendar' });
  const detail = page.locator('.cal-detail');

  // Off by default: nobody else is shown, not even those on the same shift.
  const toggle = page.getByRole('button', { name: 'Everyone there' });
  await expect(toggle).toHaveAttribute('aria-pressed', 'false');
  await cal.getByRole('button', { name: /^1 / }).click();
  await expect(detail).not.toContainText('タナカ');
  await expect(detail).not.toContainText('ヤマダ');

  // The people button turns it on: everyone there, by when they start.
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-pressed', 'true');
  const there = detail.getByRole('list', { name: 'Everyone there' }).getByRole('listitem');
  await expect(there).toHaveCount(3);
  await expect(there.nth(0)).toContainText('ヤマダ ハナ');
  await expect(there.nth(0)).toContainText('9:00–18:00');
  await expect(there.nth(1)).toContainText('Sam');
  await expect(there.nth(2)).toContainText('タナカ ユイ');
  // The bars line up even though "9:00–18:00" is narrower than "13:00–22:00".
  const bars = await detail.locator('.there-bar').evaluateAll((els) => els.map((el) => {
    const r = el.getBoundingClientRect();
    return [Math.round(r.left), Math.round(r.width)];
  }));
  expect(bars).toHaveLength(3);
  expect(new Set(bars.map(String)).size).toBe(1);
  await detail.screenshot({ path: test.info().outputPath('everyone-there.png'), animations: 'disabled' });

  // A night shift from the day before shows on an early shift.
  await cal.getByRole('button', { name: /^2 / }).click();
  await expect(there.nth(0)).toContainText('ヨル タロウ');
  await expect(there.nth(0)).toContainText('22:00–7:00');

  // The list shows it too; coming back, it's off again.
  await page.getByRole('radio', { name: 'List' }).click();
  await expect(page.getByRole('list', { name: 'Shifts' }).getByRole('list', { name: 'Everyone there' })).toHaveCount(2);
  await page.reload();
  await expect(toggle).toHaveAttribute('aria-pressed', 'false');

  // Saved, a day keeps only those there during its shift: the ones on 7B
  // and the night before were dropped.
  await page.getByRole('link', { name: /^Edit \S+ \d{4}$/ }).click();
  const days = page.getByRole('list', { name: 'Days' }).getByRole('listitem');
  const count = days.nth(0).getByRole('button', { name: /^Who’s there on / });
  await expect(count).toHaveText('2');
  await count.click();
  const sheet = page.getByRole('dialog', { name: /^Who’s there on / });
  const everyone = sheet.getByRole('list', { name: 'Everyone that day' }).getByRole('listitem');
  await expect(everyone).toHaveCount(2);
  await expect(everyone.nth(0)).not.toHaveClass('away');
  await expect(everyone.nth(1)).not.toHaveClass('away');
  await expect(sheet).not.toContainText('コバヤシ');
  await expect(sheet).not.toContainText('ヨル');
  await page.screenshot({ path: test.info().outputPath('people-sheet.png'), animations: 'disabled' });
  await sheet.getByRole('button', { name: 'Done' }).click();
  // A 9F: the one on 13F is still there, now on another shift.
  await days.nth(0).getByRole('combobox').fill('9F');
  await expect(count).toHaveText('2');
  const saving = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/api/shifts/month'));
  await page.getByRole('button', { name: /^Save / }).click();
  const sent = (await saving).postDataJSON();
  expect(sent.days[0].people.map((p) => p.name).sort()).toEqual(['タナカ ユイ', 'ヤマダ ハナ']);
  expect(sent.days[1].people).toEqual([{ name: 'ヨル タロウ', code: '22F', day_offset: -1 }]);
});
