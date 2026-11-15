// Whose shifts, how to find their row on the sheet, how long each letter's
// shift is, and the calendar feed to subscribe to in Google Calendar.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type ShiftSettings } from '../../api';
import { t } from '../../i18n';
import { server } from '../../native';
import { href } from '../../router';

export function ShiftSettingsPage() {
  const [s, setS] = useState<ShiftSettings | null>(null);
  const [person, setPerson] = useState('');
  const [onSheet, setOnSheet] = useState('');
  const [lengths, setLengths] = useState<[string, string][]>([]);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  function take(next: ShiftSettings) {
    setS(next);
    setPerson(next.person_name);
    setOnSheet(next.name_on_sheet);
    setLengths(
      Object.entries(next.lengths)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([k, v]) => [k, String(v)]),
    );
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
    void run(() => api.saveShiftSettings({ person_name: person, name_on_sheet: onSheet, lengths: table }), t('Saved.'));
  }

  const feedUrl = s?.feed_path ? `${server || location.origin}${s.feed_path}` : '';
  return (
    <section className="stack">
      <a className="back" href={href('shifts')}>
        ‹ {t('Shifts')}
      </a>
      <h1 className="display">{t('Shift settings')}</h1>
      {!s && !error && <p className="muted">{t('Loading…')}</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
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
                {t('Hours on site, break included, for the letter in a code: 13F starts at 13:00 and lasts as long as F. Check these against the key at the bottom of the sheet.')}
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
                  + {t('Letter')}
                </button>
              </div>
            </div>
            <div className="row end">
              <span className="hint grow" role="status">
                {status}
              </span>
              <button type="submit" disabled={busy}>
                {t('Save')}
              </button>
            </div>
          </form>

          <div className="card stack">
            <h2>{t('In Google Calendar')}</h2>
            <p className="muted">
              {t('Turn on the calendar link, then add it in Google Calendar on a computer: Other calendars › + › From URL. The shifts appear on your phones too, and update by themselves every few hours.')}
            </p>
            {feedUrl ? (
              <>
                <div className="row">
                  <input readOnly aria-label={t('Calendar link')} value={feedUrl} onFocus={(e) => e.target.select()} />
                  <button
                    type="button"
                    className="secondary"
                    onClick={() => navigator.clipboard?.writeText(feedUrl).then(() => setCopied(true), () => {})}
                  >
                    {copied ? t('Copied') : t('Copy')}
                  </button>
                </div>
                <span className="hint">{t('Anyone with this link can see the shifts. If it gets out, make a new one.')}</span>
                <div className="row wrap">
                  <button type="button" className="secondary" disabled={busy} onClick={() => run(() => api.shiftFeed(true), t('Made a new link. Add it again where you used the old one.'))}>
                    {t('Make a new link')}
                  </button>
                  <button type="button" className="link quiet" disabled={busy} onClick={() => run(() => api.shiftFeed(false), t('The calendar link is off.'))}>
                    {t('Turn off')}
                  </button>
                </div>
              </>
            ) : (
              <button type="button" disabled={busy} onClick={() => run(() => api.shiftFeed(true), '')}>
                {t('Turn on the calendar link')}
              </button>
            )}
          </div>
        </>
      )}
    </section>
  );
}
