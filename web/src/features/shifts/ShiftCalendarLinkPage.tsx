// Putting the shifts in Google Calendar: the calendar link, a button that
// opens Google Calendar with it filled in, and the steps, so each of you
// can add it to your own calendar.
import { useEffect, useState } from 'react';
import { BackLink } from '../../components/BackLink';
import { api, errorMessage, type ShiftSettings } from '../../api';
import { t } from '../../i18n';
import { href } from '../../router';

/** Google Calendar's "add this calendar" page, with the link filled in. */
export function googleCalendarUrl(feedUrl: string): string {
  return `https://calendar.google.com/calendar/r?cid=${encodeURIComponent(feedUrl.replace(/^https?:/, 'webcal:'))}`;
}

export function ShiftCalendarLinkPage() {
  const [s, setS] = useState<ShiftSettings | null>(null);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    api.shiftSettings().then(setS, (err) => setError(errorMessage(err)));
  }, []);

  async function feed(on: boolean, done: string) {
    setBusy(true);
    setError('');
    setStatus('');
    setCopied(false);
    try {
      setS(await api.shiftFeed(on));
      setStatus(done);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const feedUrl = s?.feed_path ? `${location.origin}${s.feed_path}` : '';
  const name = s?.person_name ? t('{name}’s shifts', { name: s.person_name }) : t('Shifts');

  function copy() {
    void navigator.clipboard?.writeText(feedUrl).then(() => setCopied(true), () => {});
  }
  async function share() {
    if (navigator.share) await navigator.share({ title: name, url: feedUrl }).catch(() => {});
    else copy();
  }

  return (
    <section className="stack">
      <BackLink href={href('shifts')} label="Shifts" />
      <h1 className="display">{t('Add to Google Calendar')}</h1>
      {!s && !error && <p className="muted">{t('Loading…')}</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {s && !feedUrl && (
        <div className="card stack">
          <button type="button" disabled={busy} onClick={() => feed(true, '')}>
            {t('Turn on the calendar link')}
          </button>
          {status && (
            <span className="hint" role="status">
              {status}
            </span>
          )}
        </div>
      )}
      {s && feedUrl && (
        <>
          <div className="card stack">
            <ol className="how">
              <li>
                <span className="how-num" aria-hidden="true">1</span>
                <div className="stack tight grow">
                  <strong>{t('On a computer, open Google Calendar')}</strong>
                  <div className="row wrap">
                    <a className="button" href={googleCalendarUrl(feedUrl)} target="_blank" rel="noopener noreferrer">
                      {t('Open Google Calendar')}
                    </a>
                  </div>
                </div>
              </li>
              <li>
                <span className="how-num" aria-hidden="true">2</span>
                <strong className="grow">{t('Click Add')}</strong>
              </li>
              <li>
                <span className="how-num" aria-hidden="true">3</span>
                <div className="stack tight grow">
                  <strong>{t('Send the link to the other one of you')}</strong>
                  <div className="row">
                    <input readOnly aria-label={t('Calendar link')} value={feedUrl} onFocus={(e) => e.target.select()} />
                    <button type="button" className="secondary" onClick={'share' in navigator ? share : copy}>
                      {'share' in navigator ? t('Share') : copied ? t('Copied') : t('Copy')}
                    </button>
                  </div>
                </div>
              </li>
            </ol>
            <p className="hint">{t('Not on your phone? Turn on Sync for “{name}” in the app’s settings.', { name })}</p>
          </div>

          <details className="other-ways">
            <summary>{t('More')}</summary>
            <div className="stack tight">
              <p className="hint">{t('By hand: Other calendars › + › From URL.')}</p>
              <p className="hint">
                <a href={feedUrl.replace(/^https?:/, 'webcal:')}>{t('Add to Apple Calendar')}</a>
              </p>
              <p className="hint">{t('Anyone with the link can see the shifts.')}</p>
              <div className="row wrap">
                <button type="button" className="secondary" disabled={busy} onClick={() => feed(true, t('Made a new link. Add it again where you used the old one.'))}>
                  {t('Make a new link')}
                </button>
                <button type="button" className="link quiet" disabled={busy} onClick={() => feed(false, t('The calendar link is off.'))}>
                  {t('Turn off')}
                </button>
              </div>
            </div>
          </details>
          {status && (
            <span className="hint" role="status">
              {status}
            </span>
          )}
        </>
      )}
    </section>
  );
}
