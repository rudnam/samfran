// Whose shifts, how to find their row on the sheet, how long each letter's
// shift is. The calendar link is on ShiftCalendarLinkPage, from the Shifts page.
import { BackLink } from '../../components/BackLink';
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type ShiftSettings } from '../../api';
import { t } from '../../i18n';
import { language } from '../../language';
import { href } from '../../router';
import { Icon } from '../../components/Icon';
import { ErrorText } from '../../components/ErrorText';
import { useLeaveGuard } from '../../components/LeaveGuard';
import { Skeleton } from '../../components/Overlay';

const suffix: Record<string, string> = { one: 'st', two: 'nd', few: 'rd', other: 'th' };

/** A day of the month as the picker says it: 15th, 15日. */
function dayOfMonth(n: number): string {
  return language() === 'ja' ? t('{n}th', { n }) : `${n}${suffix[new Intl.PluralRules('en', { type: 'ordinal' }).select(n)]}`;
}

export function ShiftSettingsPage() {
  const [s, setS] = useState<ShiftSettings | null>(null);
  const [person, setPerson] = useState('');
  const [onSheet, setOnSheet] = useState('');
  const [lengths, setLengths] = useState<[string, string][]>([]);
  const [closing, setClosing] = useState<number[]>([]);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);
  // The settings as saved, to tell whether anything here isn't.
  const [savedForm, setSavedForm] = useState('');
  const form = JSON.stringify([person, onSheet, lengths, closing]);
  useLeaveGuard(!!s && form !== savedForm);

  function take(next: ShiftSettings) {
    setS(next);
    setPerson(next.person_name);
    setOnSheet(next.name_on_sheet);
    setClosing(next.closing_days ?? [15, 31]);
    const rows = Object.entries(next.lengths)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]): [string, string] => [k, String(v)]);
    setLengths(rows);
    setSavedForm(JSON.stringify([next.person_name, next.name_on_sheet, rows, next.closing_days ?? [15, 31]]));
  }

  useEffect(() => {
    api.shiftSettings().then(take, (err) => setError(errorMessage(err)));
  }, []);

  async function run(fn: () => Promise<ShiftSettings>, done: string) {
    setBusy(true);
    setError('');
    setStatus('');
    try {
      take(await fn());
      setStatus(done);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  function save(e: FormEvent) {
    e.preventDefault();
    const table: Record<string, number> = {};
    for (const [k, v] of lengths) if (k.trim()) table[k.trim().toUpperCase()] = Number(v);
    void run(() => api.saveShiftSettings({ person_name: person, name_on_sheet: onSheet, lengths: table, closing_days: closing }), t('Saved.'));
  }

  return (
    <section className="stack">
      <BackLink href={href('shifts')} label="Shifts" />
      <h1 className="display">{t('Shift settings')}</h1>
      {!s && !error && <Skeleton rows={4} />}
      <ErrorText message={error} />
      {s && (
        <>
          <form className="card stack" onSubmit={save}>
            <div className="two">
              <label className="field">
                {t('Whose shifts')}
                <input maxLength={40} placeholder="Sam" value={person} onChange={(e) => setPerson(e.target.value)} />
              </label>
              <label className="field">
                {t('Name on the sheet')}
                <input maxLength={80} placeholder="オトル サマンタ" value={onSheet} onChange={(e) => setOnSheet(e.target.value)} />
              </label>
            </div>
            <span className="hint">{t('Exactly as printed on the sheet, so the right row is read.')}</span>

            <div className="field">
              <span>{t('Shift lengths')}</span>
              <span className="hint">
                {t('Hours per letter, break included: 13F starts at 13:00 and lasts as long as F.')}
              </span>
              <div className="lengths">
                {lengths.map(([k, v], i) => (
                  <label key={i} className="length">
                    <input
                      aria-label={t('Letter')}
                      className="letter"
                      maxLength={1}
                      value={k}
                      onChange={(e) => setLengths((l) => l.map((x, j) => (j === i ? [e.target.value, x[1]] : x)))}
                    />
                    <input
                      aria-label={t('Hours for {letter}', { letter: k })}
                      type="number"
                      min="0.5"
                      max="24"
                      step="0.25"
                      value={v}
                      onChange={(e) => setLengths((l) => l.map((x, j) => (j === i ? [x[0], e.target.value] : x)))}
                    />
                    <span className="muted small">{t('h')}</span>
                  </label>
                ))}
                <button type="button" className="secondary" onClick={() => setLengths((l) => [...l, ['', '8']])}>
                  <Icon name="add" size={18} />
                  {t('Letter')}
                </button>
              </div>
            </div>
            <div className="field">
              <span>{t('Hours due (締め申請通知)')}</span>
              <span className="hint">{t('The days of the month the hours are sent in. The calendar marks them with a red dot.')}</span>
              <div className="lengths">
                {closing.map((d, i) => (
                  <span key={i} className="length">
                    <select
                      aria-label={t('Closing day {n}', { n: i + 1 })}
                      value={d}
                      onChange={(e) => setClosing((c) => c.map((x, j) => (j === i ? Number(e.target.value) : x)))}
                    >
                      {Array.from({ length: 31 }, (_, k) => k + 1).map((n) => (
                        <option key={n} value={n}>
                          {n === 31 ? t('Last day') : dayOfMonth(n)}
                        </option>
                      ))}
                    </select>
                    <button type="button" className="link quiet" aria-label={t('Remove closing day {n}', { n: i + 1 })} onClick={() => setClosing((c) => c.filter((_, j) => j !== i))}>
                      <Icon name="close" size={18} />
                    </button>
                  </span>
                ))}
                {closing.length < 10 && (
                  <button type="button" className="secondary" onClick={() => setClosing((c) => [...c, 15])}>
                    <Icon name="add" size={18} />
                    {t('Day')}
                  </button>
                )}
              </div>
            </div>
            <div className="row end sticky-actions">
              <span className="hint grow" role="status">
                {status}
              </span>
              <button type="submit" disabled={busy}>
                {t('Save')}
              </button>
            </div>
          </form>
          <a className="card row spread" href={href('shifts/meals')}>
            <span className="grow">
              <strong className="block">{t('Meal reminders')}</strong>
              <span className="hint">{t('Breakfast, lunch and dinner times for each shift.')}</span>
            </span>
            <Icon name="chevronRight" size={20} />
          </a>
        </>
      )}
    </section>
  );
}

