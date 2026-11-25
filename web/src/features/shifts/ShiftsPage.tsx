// Sam's shifts: the month, the next shift, reading a photo of the sheet,
// and fixing days by hand. Adding them to Google Calendar is on
// ShiftCalendarLinkPage, the settings on ShiftSettingsPage.
import { useRef, useState } from 'react';
import { api, errorMessage, type ReadProgress, type ShiftDay, type ShiftMonth } from '../../api';
import { t } from '../../i18n';
import { href, navigate } from '../../router';
import { useCached } from '../../cache';
import { Icon } from '../../components/Icon';
import { Skeleton } from '../../components/Overlay';
import { addMonths, dayName, labelOf, monthName, shrinkPhoto, timesOf } from './codes';
import { EveryoneToggle, WhoThere } from './WhoThere';
import { ShiftCalendar, tone } from './ShiftCalendar';
import { setPendingRead } from './ShiftEditor';
import { googleCalendarUrl } from './ShiftCalendarLinkPage';
import { ErrorText } from '../../components/ErrorText';

// Calendar or list: remembered on this device.
type View = 'calendar' | 'list';
const viewKey = 'shifts-view';
function savedView(): View {
  try {
    return localStorage.getItem(viewKey) === 'list' ? 'list' : 'calendar';
  } catch {
    return 'calendar';
  }
}

/** The next shift, looking into next month if this one has none left. */
async function loadNext(): Promise<{ day: ShiftDay | null }> {
  const now = Date.now();
  const upcoming = (m: ShiftMonth) => m.days.find((d) => d.end && Date.parse(d.end) > now) ?? null;
  const cur = await api.shifts();
  return { day: upcoming(cur) ?? upcoming(await api.shifts(addMonths(cur.month, 1))) };
}

export function ShiftsPage({ month, day }: { month?: string; day?: string }) {
  const { data, error: loadError } = useCached(`shifts:${month ?? ''}`, () => api.shifts(month), errorMessage);
  // The next shift, above every month: tapping it opens its day. Cached, and
  // the month waits for it, so its card never drops in above a calendar
  // that's already showing.
  const nextShift = useCached('shifts-next', loadNext, errorMessage);
  const next = nextShift.data?.day ?? null;
  const ready = data !== null && (nextShift.data !== null || nextShift.error !== '');
  const [readError, setError] = useState('');
  const error = readError || loadError;
  const [reading, setReading] = useState<ReadProgress | null>(null);
  // Which calendar line was just copied: Google Calendar's address or the plain link.
  const [copied, setCopied] = useState<'google' | 'plain' | null>(null);
  function copyCalendar(which: 'google' | 'plain', feedPath: string) {
    const feed = `${location.origin}${feedPath}`;
    void navigator.clipboard?.writeText(which === 'google' ? googleCalendarUrl(feed) : feed).then(() => setCopied(which), () => {});
  }
  const [view, setView] = useState<View>(savedView);
  // Who else is at work stays hidden until the people button is pressed.
  const [everyone, showEveryone] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  function show(v: View) {
    setView(v);
    try {
      localStorage.setItem(viewKey, v);
    } catch {
      // Private browsing: it lasts until the page is left.
    }
  }

  async function readPhoto(file: File) {
    setError('');
    setReading({ stage: 'sending', sent: 0 });
    try {
      const result = await api.readShiftPhoto(await shrinkPhoto(file), setReading);
      setPendingRead(result);
      navigate('shifts/edit', { month: result.month, from: 'photo' });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setReading(null);
      if (fileInput.current) fileInput.current.value = '';
    }
  }

  const shown = data?.month ?? month ?? '';
  const who = data?.settings.person_name || t('Shifts');
  const me = data?.settings.person_name || t('You');
  const needsName = data && !data.settings.name_on_sheet;
  // A month with shifts is mostly fixed or looked at; an empty one, filled in.
  const filled = !!data && data.days.length > 0;
  return (
    <section className="stack">
      <header className="page-head">
        <div className="row spread">
          <div>
            <h1 className="display">{data?.settings.person_name ? t('{name}’s shifts', { name: who }) : t('Shifts')}</h1>
          </div>
          <a className="icon-button" href={href('shifts/settings')} aria-label={t('Settings')} title={t('Settings')}>
            <Icon name="settings" />
          </a>
        </div>
      </header>

      {next && (
        <a className="next-shift" href={href('shifts', { month: next.date.slice(0, 7), day: next.date })}>
          <span className={`shift-dot ${tone(next)}`} aria-hidden="true" />
          <span className="grow">
            <span className="eyebrow">{t('Next shift')}</span>
            <strong className="block">
              {dayName(next.date)} · {labelOf(next.label)} · <span className="nowrap">{timesOf(next)}</span>
            </strong>
          </span>
          <span className="code-chip">{next.code}</span>
          <span className="chevron"><Icon name="chevronRight" size={18} /></span>
        </a>
      )}

      <ErrorText message={error} />

      <div className="card stack tight">
        <div className="row spread">
          <a className="link-btn" href={href('shifts', { month: addMonths(shown, -1) })} aria-label={t('Previous month')}>
            <Icon name="chevronLeft" size={22} />
          </a>
          <h2>{shown && monthName(shown)}</h2>
          <a className="link-btn" href={href('shifts', { month: addMonths(shown, 1) })} aria-label={t('Next month')}>
            <Icon name="chevronRight" size={22} />
          </a>
        </div>
        <div className="view-bar">
        <div className="segmented" role="radiogroup" aria-label={t('View')}>
          <button type="button" role="radio" aria-checked={view === 'calendar'} className={view === 'calendar' ? 'on' : ''} onClick={() => show('calendar')}>
            {t('Calendar')}
          </button>
          <button type="button" role="radio" aria-checked={view === 'list'} className={view === 'list' ? 'on' : ''} onClick={() => show('list')}>
            {t('List')}
          </button>
        </div>
        <EveryoneToggle on={everyone} onChange={showEveryone} />
        </div>
        {!ready && !error && <Skeleton rows={6} />}
        {ready && data && data.days.length === 0 && (
          <div className="empty flat">
            <p className="empty-title">{t('No shifts for this month')}</p>
            <p className="muted">{t('Read a photo of the sheet, or enter the days by hand.')}</p>
          </div>
        )}
        {ready && data && data.days.length > 0 && view === 'calendar' && (
          <ShiftCalendar
            key={`${data.month}:${day ?? ''}`}
            month={data.month}
            pick={day}
            days={data.days}
            closing={data.closing_days ?? data.settings.closing_days ?? [15, 31]}
            who={me}
            everyone={everyone}
           
          />
        )}
        {ready && data && data.days.length > 0 && view === 'list' && (
          <ul className="shift-list" aria-label={t('Shifts')}>
            {data.days.map((d) => (
              <li
                key={d.date}
                className={[d.kind === 'work' ? '' : 'quiet', d.date === day ? 'picked' : ''].join(' ').trim() || undefined}
                aria-current={d.date === day ? 'date' : undefined}
              >
                <span className="shift-date">{dayName(d.date)}</span>
                <span className={`shift-dot ${tone(d)}`} aria-hidden="true" />
                <span className="grow">
                  {labelOf(d.label)}
                  {d.problem && <span className="error small block">{t('Unknown length: set it in Settings')}</span>}
                </span>
                <span className="shift-time">{timesOf(d)}</span>
                <span className="code-chip">{d.code}</span>
                <WhoThere day={d} who={me} everyone={everyone} />
              </li>
            ))}
          </ul>
        )}
      </div>

      {reading && <ReadingCard progress={reading} />}
      <div className="actions">
        <input
          ref={fileInput}
          type="file"
          accept="image/*"
          hidden
          aria-label={t('Photo of the sheet')}
          onChange={(e) => e.target.files?.[0] && readPhoto(e.target.files[0])}
        />
        {filled && (
          <a className="button secondary" href={href('shifts/edit', { month: shown })}>
            <Icon name="edit" size={20} />
            {t('Edit {month}', { month: monthName(shown) })}
          </a>
        )}
        {data?.settings.reader && !reading && (
          <button type="button" className={filled ? 'secondary' : ''} disabled={!!needsName} onClick={() => fileInput.current?.click()}>
            <Icon name="camera" size={20} />
            {filled ? t('Read a new sheet') : t('Read a photo of the sheet')}
          </button>
        )}
        {data && !filled && (
          <a className="button secondary" href={href('shifts/edit', { month: shown })}>
            <Icon name="edit" size={20} />
            {t('Enter by hand')}
          </a>
        )}
        {data && !data.settings.feed_path && (
          <a className="button secondary" href={href('shifts/calendar')}>
            <Icon name="calendar" size={20} />
            {t('Add to Google Calendar')}
          </a>
        )}
      </div>
      {/* Once it's on there's nothing to do after each new month, so it's only a quiet line: it copies
          the Open Google Calendar address (or the plain link, for any other calendar) to send. */}
      {data?.settings.feed_path && (
        <div className="row tight-row muted">
          <button type="button" className="link quiet inline" onClick={() => copyCalendar('google', data.settings.feed_path)}>
            {copied === 'google' ? t('Copied') : t('Share via Google Calendar')}
          </button>
          <span aria-hidden="true">·</span>
          <button type="button" className="link quiet inline" onClick={() => copyCalendar('plain', data.settings.feed_path)}>
            {copied === 'plain' ? t('Copied') : t('Other calendars')}
          </button>
        </div>
      )}

      {needsName && data?.settings.reader && (
        <p className="hint">
          {t('To read photos, first set the name as it’s printed on the sheet in')} <a href={href('shifts/settings')}>{t('Settings')}</a>.
        </p>
      )}
    </section>
  );
}

/** How far reading the photo has got: sent, found, then the days one by one. */
function ReadingCard({ progress }: { progress: ReadProgress }) {
  const sending = progress.stage === 'sending';
  const found = progress.stage === 'reading' ? progress.name_as_read : undefined;
  const read = progress.stage === 'reading' ? progress.days_read : 0;
  const of = progress.stage === 'reading' ? progress.days_in_month : 0;
  const step = (done: boolean, now: boolean, text: string) => (
    <li className={done ? 'done' : now ? 'now' : 'later'}>
      <span className="step-mark" aria-hidden="true">{done ? '✓' : now ? '●' : '○'}</span>
      {text}
    </li>
  );
  return (
    <div className="card stack tight reading" role="status" aria-label={t('Reading the sheet…')}>
      <strong>{t('Reading the sheet…')}</strong>
      <ul className="steps">
        {step(!sending, sending, sending && progress.sent > 0 ? t('Sending the photo: {percent}%', { percent: Math.round(progress.sent * 100) }) : t('Sending the photo'))}
        {step(!!found, !sending && !found, found ? t('Found {name} on the sheet', { name: found }) : t('Finding the row on the sheet'))}
        {step(false, !!found, of ? t('Reading the days: {read} of {of}', { read, of }) : t('Reading the days'))}
      </ul>
      {of > 0 && (
        <div className="day-progress" aria-hidden="true">
          {Array.from({ length: of }, (_, i) => (
            <span key={i} className={i < read ? 'on' : ''} />
          ))}
        </div>
      )}
      <p className="hint">{t('Usually about a minute.')}</p>
    </div>
  );
}
