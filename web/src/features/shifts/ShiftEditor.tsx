// Checking a month before it's saved: what the photo reader found, or the
// saved month to fix by hand. Every day of the month has a box for its code,
// and shows what the code means as it's typed.
import { useEffect, useState } from 'react';
import { api, errorMessage, type ShiftMonth } from '../../api';
import { t } from '../../i18n';
import { href, replaceRoute } from '../../router';
import { clock, datesOf, dayName, explain, labelOf, monthName, normalize } from './codes';

// The photo reader's answer, handed from ShiftsPage to this page.
let pendingRead: ShiftMonth | null = null;
export function setPendingRead(m: ShiftMonth) {
  pendingRead = m;
}

// Names are separated by commas (a name itself has a space in it).
function splitNames(text: string): string[] {
  return text.split(/[,、，]/).map((n) => n.trim()).filter(Boolean);
}

const common = ['7F', '9F', '13F', '22F', '普', '法', '有休'];

export function ShiftEditor({ month, fromPhoto }: { month: string; fromPhoto: boolean }) {
  const [base, setBase] = useState<ShiftMonth | null>(null);
  const [codes, setCodes] = useState<Record<string, string>>({});
  // Coworkers typed by hand, per day, as one comma-separated line.
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const read = fromPhoto && pendingRead?.month === month ? pendingRead : null;
    pendingRead = null;
    const load = read ? Promise.resolve(read) : api.shifts(month);
    load.then(
      (m) => {
        setBase(m);
        setCodes(Object.fromEntries(m.days.map((d) => [d.date, d.code])));
      },
      (err) => setError(errorMessage(err)),
    );
  }, [month, fromPhoto]);

  // Who's on the same shift: what was typed, else as read or saved. The read
  // names only stay while the day's code is unchanged (a different code is a
  // different shift); typed names are the user's call and always stay.
  const original = new Map(base?.days.map((d) => [d.date, d]) ?? []);
  function withFor(date: string, code: string): string[] | undefined {
    const d = original.get(date);
    if (typed[date] !== undefined) return splitNames(typed[date]);
    return d?.with?.length && normalize(d.code) === normalize(code) ? d.with : undefined;
  }

  // Names fixed while checking a scan, as read → corrected, so the next scan
  // gets them right. Only where the same number of names went as came, so
  // each pairs with the one it replaced.
  function corrections(days: { date: string; with?: string[] }[]) {
    const out = new Map<string, string>();
    for (const d of days) {
      const was = original.get(d.date)?.with ?? [];
      const now = d.with ?? [];
      const removed = was.filter((n) => !now.includes(n));
      const added = now.filter((n) => !was.includes(n));
      if (removed.length === added.length) removed.forEach((from, i) => out.set(from, added[i]));
    }
    return [...out].map(([from, to]) => ({ from, to }));
  }

  async function save() {
    setBusy(true);
    setError('');
    try {
      const days = Object.entries(codes)
        .map(([date, code]) => ({ date, code: normalize(code), with: withFor(date, code) }))
        .filter((d) => d.code);
      await api.saveShifts(month, days, fromPhoto ? corrections(days) : undefined);
      replaceRoute('shifts', { month });
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  if (!base) {
    return (
      <section className="stack">
        <a className="back" href={href('shifts', { month })}>
          ‹ {t('Shifts')}
        </a>
        {error ? <p role="alert" className="error">{error}</p> : <p className="muted">{t('Loading…')}</p>}
      </section>
    );
  }
  const read = fromPhoto && base.name_as_read !== undefined;
  return (
    <section className="stack">
      <a className="back" href={href('shifts', { month })}>
        ‹ {t('Shifts')}
      </a>
      <h1 className="display">{read ? t('Check the shifts') : monthName(month)}</h1>
      {read && (
        <div className="notice stack tight" role="status">
          <span>
            {t('Read {name}’s row for {month}. Compare it with the sheet and fix any day before saving.', {
              name: base.name_as_read || base.settings.name_on_sheet,
              month: monthName(month),
            })}
          </span>
          {base.notes && <span className="muted small">{base.notes}</span>}
        </div>
      )}
      <datalist id="shift-codes">
        {common.map((c) => (
          <option key={c} value={c} />
        ))}
      </datalist>
      <ul className="card shift-edit" aria-label={t('Days')}>
        {datesOf(month).map((date) => {
          const code = codes[date] ?? '';
          const meaning = code ? explain(code, base.settings.lengths) : null;
          return (
            <li key={date}>
              <label className="shift-date" htmlFor={`code-${date}`}>
                {dayName(date)}
              </label>
              <input
                id={`code-${date}`}
                className="code-input"
                list="shift-codes"
                maxLength={12}
                value={code}
                aria-label={t('Code for {day}', { day: dayName(date) })}
                onChange={(e) => setCodes((c) => ({ ...c, [date]: e.target.value }))}
              />
              <span className="grow small">
                {meaning && (
                  <>
                    {labelOf(meaning.label)}
                    {meaning.hours && <span className="muted"> · {clock(meaning.hours[0])}–{clock(meaning.hours[1])}</span>}
                    {meaning.problem && <span className="error"> · {t('Unknown length: set it in Settings')}</span>}
                  </>
                )}
                {code && (
                  <input
                    className="with-input"
                    value={typed[date] ?? withFor(date, code)?.join(', ') ?? ''}
                    maxLength={400}
                    placeholder={t('Who else is on this shift (separate with commas)')}
                    aria-label={t('Who is with you on {day}', { day: dayName(date) })}
                    onChange={(e) => setTyped((w) => ({ ...w, [date]: e.target.value }))}
                  />
                )}
              </span>
            </li>
          );
        })}
      </ul>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="row end sticky-actions">
        <a className="button secondary" href={href('shifts', { month })}>
          {t('Cancel')}
        </a>
        <button type="button" disabled={busy} onClick={save}>
          {t('Save {month}', { month: monthName(month) })}
        </button>
      </div>
    </section>
  );
}
