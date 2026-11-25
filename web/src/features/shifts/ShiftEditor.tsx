// Checking a month before it's saved: what the photo reader found, or the
// saved month to fix by hand. Every day of the month has a box for its code,
// and shows what the code means as it's typed.
import { BackLink } from '../../components/BackLink';
import { useEffect, useState } from 'react';
import { api, errorMessage, type ShiftMonth } from '../../api';
import { t } from '../../i18n';
import { href, replaceRoute } from '../../router';
import { clock, datesOf, dayName, explain, hoursAround, labelOf, monthName, normalize } from './codes';
import { isThere, PeopleSheet, personRow, type PersonRow } from './PeopleSheet';
import { Icon } from '../../components/Icon';
import { ErrorText } from '../../components/ErrorText';
import { useLeaveGuard } from '../../components/LeaveGuard';
import { Skeleton } from '../../components/Overlay';

// The photo reader's answer, handed from ShiftsPage to this page.
let pendingRead: ShiftMonth | null = null;
export function setPendingRead(m: ShiftMonth) {
  pendingRead = m;
}

const common = ['7F', '9F', '13F', '22F', '普', '法', '有休'];

/** The usual closing days as they fall in a month: a day past its end is its last day. */
export function usualClosing(month: string, usual: number[]): number[] {
  const last = datesOf(month).length;
  return [...new Set(usual.map((d) => Math.min(d, last)))].sort((a, b) => a - b);
}

export function ShiftEditor({ month, fromPhoto }: { month: string; fromPhoto: boolean }) {
  const [base, setBase] = useState<ShiftMonth | null>(null);
  const [codes, setCodes] = useState<Record<string, string>>({});
  // Everyone at work around each day, as read or saved, and as fixed here.
  const [people, setPeople] = useState<Record<string, PersonRow[]>>({});
  // The day whose people are open, and who was listed first when it opened.
  const [open, setOpen] = useState<{ date: string; shown: Set<number> } | null>(null);
  // 締め申請通知 for this month: null until it's loaded; sent only once changed.
  const [closing, setClosing] = useState<number[] | null>(null);
  const [closingChanged, setClosingChanged] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // The days as loaded: leaving with them changed, or with a photo's read not yet saved, asks first.
  const [loaded, setLoaded] = useState('');
  const days = JSON.stringify([codes, people]);
  const release = useLeaveGuard(!!base && (fromPhoto || closingChanged || days !== loaded));

  useEffect(() => {
    const read = fromPhoto && pendingRead?.month === month ? pendingRead : null;
    pendingRead = null;
    const load = read ? Promise.resolve(read) : api.shifts(month);
    load.then(
      (m) => {
        const c = Object.fromEntries(m.days.map((d) => [d.date, d.code]));
        const p = Object.fromEntries(m.days.map((d) => [d.date, (d.people ?? []).map((x) => personRow(x, fromPhoto))]));
        setBase(m);
        setCodes(c);
        setPeople(p);
        setLoaded(JSON.stringify([c, p]));
        // A photo read doesn't carry the month's closing days; the saved month does.
        (m.closing_days ? Promise.resolve(m) : api.shifts(month)).then(
          (saved) => setClosing(saved.closing_days ?? usualClosing(month, saved.settings.closing_days ?? [15, 31])),
          () => setClosing(usualClosing(month, m.settings.closing_days ?? [15, 31])),
        );
      },
      (err) => setError(errorMessage(err)),
    );
  }, [month, fromPhoto]);

  function changeClosing(next: number[]) {
    setClosing(next);
    setClosingChanged(true);
  }

  const lengths = base?.settings.lengths ?? {};
  const hoursOf = (date: string) => hoursAround(codes[date] ?? '', 0, lengths);
  const thereOn = (date: string) => (people[date] ?? []).filter((p) => isThere(p, codes[date] ?? '', hoursOf(date), lengths));

  // A day's people open with those there during its shift listed first.
  function openDay(date: string) {
    setOpen({ date, shown: new Set(thereOn(date).map((p) => p.key)) });
  }
  function addPerson(date: string) {
    const added = personRow({ name: '', code: normalize(codes[date] ?? '') }, false);
    setOpen((o) => o && { ...o, shown: new Set([...o.shown, added.key]) });
    setPeople((p) => ({ ...p, [date]: [...(p[date] ?? []), added] }));
  }

  // Names fixed while checking a scan, as read → corrected, so the next scan
  // gets them right.
  function corrections() {
    const out = new Map<string, string>();
    for (const rows of Object.values(people)) {
      for (const p of rows) {
        const to = p.name.trim();
        if (p.read && to && to !== p.read) out.set(p.read, to);
      }
    }
    return [...out].map(([from, to]) => ({ from, to }));
  }

  async function save() {
    setBusy(true);
    setError('');
    try {
      const days = Object.entries(codes)
        .map(([date, code]) => ({
          date,
          code: normalize(code),
          // Everyone, there during the shift or not: the server works out who's shown.
          people: (people[date] ?? [])
            .filter((p) => p.name.trim())
            .map((p) => ({ name: p.name.trim(), code: normalize(p.code), day_offset: p.day_offset })),
        }))
        .filter((d) => d.code);
      await api.saveShifts(month, days, fromPhoto ? corrections() : undefined, closingChanged && closing ? closing : undefined);
      release();
      replaceRoute('shifts', { month });
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  if (!base) {
    return (
      <section className="stack">
        <BackLink href={href('shifts', { month })} label="Shifts" />
        {error ? <ErrorText message={error} /> : <Skeleton rows={6} />}
      </section>
    );
  }
  const read = fromPhoto && base.name_as_read !== undefined;
  return (
    <section className="stack">
      <BackLink href={href('shifts', { month })} label="Shifts" />
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
          const working = meaning?.kind === 'work';
          const there = working ? thereOn(date).length : 0;
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
                  <span className="meaning">
                    {labelOf(meaning.label)}
                    {meaning.hours && <span className="muted"> · {clock(meaning.hours[0])}–{clock(meaning.hours[1])}</span>}
                  </span>
                )}
                {meaning?.problem && <span className="error">{t('Unknown length: set it in Settings')}</span>}
              </span>
              {working && (
                <button
                  type="button"
                  className={`people-count${there ? '' : ' none'}`}
                  aria-label={t('Who’s there on {day}', { day: dayName(date) }) + (there ? ` (${there})` : '')}
                  onClick={() => openDay(date)}
                >
                  <PeopleIcon />
                  <span aria-hidden="true">{there || '+'}</span>
                </button>
              )}
            </li>
          );
        })}
      </ul>
      {open && (
        <PeopleSheet
          date={open.date}
          code={codes[open.date] ?? ''}
          hours={hoursOf(open.date)}
          rows={people[open.date] ?? []}
          shownKeys={open.shown}
          lengths={lengths}
          names={[...new Set(Object.entries(people).flatMap(([d, rows]) => (d === open.date ? [] : rows.map((p) => p.name.trim()).filter(Boolean))))]}
          onChange={(rows) => setPeople((p) => ({ ...p, [open.date]: rows }))}
          onAdd={() => addPerson(open.date)}
          onClose={() => setOpen(null)}
        />
      )}
      {closing && <ClosingDays month={month} days={closing} usual={usualClosing(month, base.settings.closing_days ?? [15, 31])} onChange={changeClosing} />}
      <ErrorText message={error} />
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

/** 締め申請通知 for one month: the days its hours are sent in, saved with its shifts. */
function ClosingDays({ month, days, usual, onChange }: { month: string; days: number[]; usual: number[]; onChange: (days: number[]) => void }) {
  const dates = datesOf(month);
  const isUsual = days.join() === usual.join();
  const free = dates.map((_, i) => i + 1).find((n) => !days.includes(n));
  return (
    <div className="card stack tight" role="group" aria-labelledby="closing-title">
      <div className="row spread">
        <h2 id="closing-title">
          <span className="cal-flag" aria-hidden="true" /> {t('Hours due (締め申請通知)')}
        </h2>
        {!isUsual && (
          <button type="button" className="link" onClick={() => onChange(usual)}>
            {t('Use the usual days')}
          </button>
        )}
      </div>
      <span className="hint">
        {isUsual ? t('The usual days, from Settings. Change them here for {month} only.', { month: monthName(month) }) : t('Only for {month}. Other months keep the usual days.', { month: monthName(month) })}
      </span>
      <div className="lengths">
        {days.map((d, i) => (
          <span key={i} className="length">
            <select
              aria-label={t('Closing day {n}', { n: i + 1 })}
              value={d}
              onChange={(e) => onChange([...new Set(days.map((x, j) => (j === i ? Number(e.target.value) : x)))].sort((a, b) => a - b))}
            >
              {dates.map((date, k) => (
                <option key={date} value={k + 1}>
                  {dayName(date)}
                </option>
              ))}
            </select>
            <button type="button" className="link quiet" aria-label={t('Remove closing day {n}', { n: i + 1 })} onClick={() => onChange(days.filter((_, j) => j !== i))}>
              <Icon name="close" size={18} />
            </button>
          </span>
        ))}
        {days.length < 10 && free !== undefined && (
          <button type="button" className="secondary" onClick={() => onChange([...days, free].sort((a, b) => a - b))}>
            <Icon name="add" size={18} />
            {t('Day')}
          </button>
        )}
      </div>
      {days.length === 0 && <span className="muted small">{t('No 締め申請通知 this month.')}</span>}
    </div>
  );
}

/** The people icon of the Shifts page's button, small. */
function PeopleIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="9" cy="8" r="3.5" />
      <circle cx="17" cy="9" r="2.5" />
      <path d="M3 20c0-3.5 2.7-6 6-6s6 2.5 6 6M15 14.5c3 0 6 1.8 6 5.5" />
    </svg>
  );
}
