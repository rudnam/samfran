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
      <p className="muted">{t('The shifts show up in Google Calendar next to everything else, on your phone and computer, and stay up to date by themselves.')}</p>
      {!s && !error && <p className="muted">{t('Loading…')}</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {s && !feedUrl && (
        <div className="card stack">
          <p>{t('First, turn on the calendar link. It’s a private address only for these shifts.')}</p>
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
                  <strong>{t('Open Google Calendar with the link')}</strong>
                  <span className="hint">{t('Easiest on a computer: the Google Calendar phone app can’t add a calendar from a link. Sign in with your own Google account.')}</span>
                  <div className="row wrap">
                    <a className="button" href={googleCalendarUrl(feedUrl)} target="_blank" rel="noopener noreferrer">
                      {t('Open Google Calendar')}
                    </a>
                  </div>
                </div>
              </li>
              <li>
                <span className="how-num" aria-hidden="true">2</span>
                <div className="stack tight grow">
                  <strong>{t('Click Add')}</strong>
                  <span className="hint">{t('“{name}” then shows under Other calendars.', { name })}</span>
                </div>
              </li>
              <li>
                <span className="how-num" aria-hidden="true">3</span>
                <div className="stack tight grow">
                  <strong>{t('See it on your phone')}</strong>
                  <span className="hint">{t('It appears in the Google Calendar app by itself. If it doesn’t, open the app’s Settings, tap “{name}” and turn on Sync.', { name })}</span>
                </div>
              </li>
              <li>
                <span className="how-num" aria-hidden="true">4</span>
                <div className="stack tight grow">
                  <strong>{t('Send the link to the other one of you')}</strong>
                  <span className="hint">{t('Each of you adds it to your own Google Calendar, the same way.')}</span>
                  <div className="row">
                    <input readOnly aria-label={t('Calendar link')} value={feedUrl} onFocus={(e) => e.target.select()} />
                    <button type="button" className="secondary" onClick={copy}>
                      {copied ? t('Copied') : t('Copy')}
                    </button>
                  </div>
                  {'share' in navigator && (
                    <div className="row wrap">
                      <button type="button" className="secondary" onClick={share}>
                        {t('Share')}
                      </button>
                    </div>
                  )}
                </div>
              </li>
            </ol>
            <p className="hint">{t('Google checks the link for changes a few times a day, so a changed shift can take up to a day to show.')}</p>
          </div>

          <details className="other-ways card">
            <summary>{t('If the button doesn’t work, or for another calendar app')}</summary>
            <div className="stack tight">
              <p className="hint">{t('In Google Calendar on a computer: next to Other calendars, click + › From URL, paste the link above and click Add calendar.')}</p>
              <p className="hint">{t('On an iPhone or a Mac:')} <a href={feedUrl.replace(/^https?:/, 'webcal:')}>{t('Add to Apple Calendar')}</a></p>
              <p className="hint">{t('Other apps: look for “subscribe” or “add calendar from URL” and paste the link.')}</p>
            </div>
          </details>

          <div className="card stack">
            <h2>{t('The link')}</h2>
            <span className="hint">{t('Anyone with this link can see the shifts. If it gets out, make a new one.')}</span>
            <div className="row wrap">
              <button type="button" className="secondary" disabled={busy} onClick={() => feed(true, t('Made a new link. Add it again where you used the old one.'))}>
                {t('Make a new link')}
              </button>
              <button type="button" className="link quiet" disabled={busy} onClick={() => feed(false, t('The calendar link is off.'))}>
                {t('Turn off')}
              </button>
            </div>
            {status && (
              <span className="hint" role="status">
                {status}
              </span>
            )}
          </div>
        </>
      )}
    </section>
  );
}
