// Putting the shifts in Google Calendar: the calendar link, a button that
// opens Google Calendar with it filled in, and the steps, so each of you
// can add it to your own calendar.
import { useEffect, useState } from 'react';
import { BackLink } from '../../components/BackLink';
import { api, errorMessage, type ShiftSettings } from '../../api';
import { t } from '../../i18n';
import { href } from '../../router';
import { ErrorText } from '../../components/ErrorText';
import { Skeleton } from '../../components/Overlay';

/** Google Calendar's "add this calendar" page, with the link filled in. */
export function googleCalendarUrl(feedUrl: string): string {
  return `https://calendar.google.com/calendar/r?cid=${encodeURIComponent(feedUrl.replace(/^https?:/, 'webcal:'))}`;
}

export function ShiftCalendarLinkPage() {
  const [s, setS] = useState<ShiftSettings | null>(null);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);

  // The link is always on: it's made the first time this page is opened.
  useEffect(() => {
    api
      .shiftSettings()
      .then((st) => (st.feed_path ? st : api.shiftFeed(true)))
      .then(setS, (err) => setError(errorMessage(err)));
  }, []);

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
      {!feedUrl && !error && <Skeleton rows={3} />}
      <ErrorText message={error} />
      {s && feedUrl && (
        <>
          <div className="card stack">
            <ol className="how">
              <li>
                <span className="how-num" aria-hidden="true">1</span>
                <div className="stack tight grow">
                  <div className="row wrap">
                    <a className="button" href={googleCalendarUrl(feedUrl)} target="_blank" rel="noopener noreferrer">
                      {t('Open Google Calendar')}
                    </a>
                  </div>
                  {/* The phone app can't add a calendar from a link; the website can, as a desktop page. */}
                  <span className="hint">{t('On a phone: the browser’s ⋮ menu › Desktop site.')}</span>
                </div>
              </li>
              <li>
                <span className="how-num" aria-hidden="true">2</span>
                <strong className="grow">{t('Press Add')}</strong>
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
            </div>
          </details>
        </>
      )}
    </section>
  );
}
