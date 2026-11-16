// Sam's shifts: the month, the next shift, reading a photo of the sheet,
// and fixing days by hand. The calendar feed and settings are on
// ShiftSettingsPage.
import { useEffect, useRef, useState } from 'react';
import { api, errorMessage, type ShiftDay, type ShiftMonth } from '../../api';
import { t } from '../../i18n';
import { href, navigate } from '../../router';
import { useCached } from '../../cache';
import { Skeleton } from '../../components/Overlay';
import { addMonths, dayName, labelOf, monthName, shrinkPhoto, timesOf } from './codes';
import { WithNames } from './WithNames';
import { ShiftCalendar, tone } from './ShiftCalendar';
import { setPendingRead } from './ShiftEditor';

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

export function ShiftsPage({ month }: { month?: string }) {
  const { data, error: loadError } = useCached(`shifts:${month ?? ''}`, () => api.shifts(month), errorMessage);
  const [next, setNext] = useState<ShiftDay | null>(null);
  const [readError, setError] = useState('');
  const error = readError || loadError;
  const [reading, setReading] = useState(false);
  const [view, setView] = useState<View>(savedView);
  const fileInput = useRef<HTMLInputElement>(null);

  function show(v: View) {
    setView(v);
    try {
      localStorage.setItem(viewKey, v);
    } catch {
      // Private browsing: it lasts until the page is left.
    }
  }

  // The next shift, looking into next month if this one has none left.
  useEffect(() => {
    if (month) return; // only on the current month's page
    const now = Date.now();
    const upcoming = (m: ShiftMonth) => m.days.find((d) => d.end && Date.parse(d.end) > now) ?? null;
    api.shifts().then(async (cur) => {
      const found = upcoming(cur);
      setNext(found ?? upcoming(await api.shifts(addMonths(cur.month, 1))));
    }, () => {});
  }, [month]);

  async function readPhoto(file: File) {
    setError('');
    setReading(true);
    try {
      const result = await api.readShiftPhoto(await shrinkPhoto(file));
      setPendingRead(result);
      navigate('shifts/edit', { month: result.month, from: 'photo' });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setReading(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  }

  const shown = data?.month ?? month ?? '';
  const who = data?.settings.person_name || t('Shifts');
  const needsName = data && !data.settings.name_on_sheet;
  return (
    <section className="stack">
      <header className="page-head">
        <div className="row spread">
          <div>
            <h1 className="display">{data?.settings.person_name ? t('{name}’s shifts', { name: who }) : t('Shifts')}</h1>
          </div>
          <a className="button secondary" href={href('shifts/settings')}>
            {t('Settings')}
          </a>
        </div>
      </header>

      {next && (
        <div className="next-shift" role="status">
          <span className={`shift-dot ${tone(next)}`} aria-hidden="true" />
          <span className="grow">
            <span className="eyebrow">{t('Next shift')}</span>
            <strong className="block">
              {dayName(next.date)} · {labelOf(next.label)} · {timesOf(next)}
            </strong>
            <WithNames names={next.with} className="with block" />
          </span>
          <span className="code-chip">{next.code}</span>
        </div>
      )}

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <div className="card stack tight">
        <div className="row spread">
          <a className="link-btn" href={href('shifts', { month: addMonths(shown, -1) })} aria-label={t('Previous month')}>
            ‹
          </a>
          <h2>{shown && monthName(shown)}</h2>
          <a className="link-btn" href={href('shifts', { month: addMonths(shown, 1) })} aria-label={t('Next month')}>
            ›
          </a>
        </div>
        <div className="segmented" role="radiogroup" aria-label={t('View')}>
          <button type="button" role="radio" aria-checked={view === 'calendar'} className={view === 'calendar' ? 'on' : ''} onClick={() => show('calendar')}>
            {t('Calendar')}
          </button>
          <button type="button" role="radio" aria-checked={view === 'list'} className={view === 'list' ? 'on' : ''} onClick={() => show('list')}>
            {t('List')}
          </button>
        </div>
        {!data && !error && <Skeleton rows={6} />}
        {data && data.days.length === 0 && (
          <div className="empty flat">
            <p className="empty-title">{t('No shifts for this month')}</p>
            <p className="muted">{t('Read a photo of the sheet, or enter the days by hand.')}</p>
          </div>
        )}
        {data && data.days.length > 0 && view === 'calendar' && <ShiftCalendar key={data.month} month={data.month} days={data.days} closing={data.settings.closing_days ?? [15, 31]} />}
        {data && data.days.length > 0 && view === 'list' && (
          <ul className="shift-list" aria-label={t('Shifts')}>
            {data.days.map((d) => (
              <li key={d.date} className={d.kind === 'work' ? '' : 'quiet'}>
                <span className="shift-date">{dayName(d.date)}</span>
                <span className={`shift-dot ${tone(d)}`} aria-hidden="true" />
                <span className="grow">
                  {labelOf(d.label)}
                  {d.problem && <span className="error small block">{t('Unknown length: set it in Settings')}</span>}
                </span>
                <span className="shift-time">{timesOf(d)}</span>
                <span className="code-chip">{d.code}</span>
                <WithNames names={d.with} className="with small row-note" />
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="row wrap">
        <input
          ref={fileInput}
          type="file"
          accept="image/*"
          hidden
          aria-label={t('Photo of the sheet')}
          onChange={(e) => e.target.files?.[0] && readPhoto(e.target.files[0])}
        />
        {data?.settings.reader && (
          <button type="button" disabled={reading || !!needsName} onClick={() => fileInput.current?.click()}>
            {reading ? t('Reading the sheet…') : t('Read a photo of the sheet')}
          </button>
        )}
        <a className="button secondary" href={href('shifts/edit', { month: shown })}>
          {t('Edit by hand')}
        </a>
      </div>
      {reading && <p className="hint">{t('This takes up to a minute. The photo isn’t kept.')}</p>}
      {needsName && data?.settings.reader && (
        <p className="hint">
          {t('To read photos, first set the name as it’s printed on the sheet in')} <a href={href('shifts/settings')}>{t('Settings')}</a>.
        </p>
      )}
    </section>
  );
}
