// The month as a calendar: one square per day, coloured by the kind of
// shift, with today ringed. Tapping a day shows its times underneath.
import { useState } from 'react';
import type { ShiftDay } from '../../api';
import { t } from '../../i18n';
import { locale } from '../../language';
import { datesOf, dayName, labelOf, timesOf } from './codes';
import { WhoThere } from './WhoThere';

/** Today as YYYY-MM-DD, on this device. */
function today(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** What fits in a square: 早, 遅, 夜, 休… or the code itself for anything else. */
function mark(d: ShiftDay): string {
  const label = labelOf(d.label);
  return label === d.label ? d.code : label[0];
}

/** The colour class a day's kind of shift gets (styles.css, .shift-dot and .cal-day). */
export function tone(d: ShiftDay): string {
  return d.kind === 'work' ? d.label.toLowerCase() : d.kind;
}

/** 締め申請通知: a day the hours are sent in. A closing day past the month's end means its last day. */
export function isClosingDay(date: string, closing: number[]): boolean {
  const [y, m, d] = date.split('-').map(Number);
  const last = new Date(y, m, 0).getDate();
  return closing.some((c) => Math.min(c, last) === d);
}

export function ShiftCalendar({
  month,
  days,
  closing,
  who,
  everyone,
  pick,
}: {
  month: string;
  /** A day to open with (the next shift's), instead of today. */
  pick?: string;
  days: ShiftDay[];
  closing: number[];
  who: string;
  everyone: boolean;
}) {
  const now = today();
  const [picked, setPicked] = useState(pick?.startsWith(month) ? pick : now.startsWith(month) ? now : '');
  const byDate = new Map(days.map((d) => [d.date, d]));
  const dates = datesOf(month);
  const [y, m] = month.split('-').map(Number);
  const lead = new Date(y, m - 1, 1).getDay(); // squares before the 1st; weeks start on Sunday
  // 4 January 1970 was a Sunday.
  const weekdays = Array.from({ length: 7 }, (_, i) => new Date(1970, 0, 4 + i).toLocaleDateString(locale(), { weekday: 'narrow' }));
  const shown = picked ? byDate.get(picked) : undefined;

  return (
    <div className="stack tight">
      <div className="cal" role="group" aria-label={t('Calendar')}>
        {weekdays.map((w, i) => (
          <span key={`w${i}`} className="cal-head" aria-hidden="true">
            {w}
          </span>
        ))}
        {Array.from({ length: lead }, (_, i) => (
          <span key={`b${i}`} />
        ))}
        {dates.map((date) => {
          const d = byDate.get(date);
          const isClosing = isClosingDay(date, closing);
          const what = [d ? [labelOf(d.label), timesOf(d)].filter(Boolean).join(' ') : t('Nothing entered'), isClosing ? t('Hours due (締め申請通知)') : '']
            .filter(Boolean)
            .join(', ');
          return (
            <button
              key={date}
              type="button"
              className={`cal-day ${d ? tone(d) : 'none'}${date === now ? ' today' : ''}`}
              aria-pressed={date === picked}
              aria-current={date === now ? 'date' : undefined}
              aria-label={`${dayName(date)}: ${what}`}
              onClick={() => setPicked(date)}
            >
              <span className="cal-num">{Number(date.slice(8))}</span>
              <span className="cal-mark">{d ? mark(d) : ''}</span>
              {isClosing && <span className="cal-flag" aria-hidden="true" />}
            </button>
          );
        })}
      </div>
      {picked && (
        <div className="cal-detail" role="status">
          <span className="shift-date">{dayName(picked)}</span>
          {shown ? (
            <>
              <span className={`shift-dot ${tone(shown)}`} aria-hidden="true" />
              <span className="grow">
                {labelOf(shown.label)}
                {shown.problem && <span className="error small block">{t('Unknown length: set it in Settings')}</span>}
              </span>
              <span className="shift-time">{timesOf(shown)}</span>
              <span className="code-chip">{shown.code}</span>
              <WhoThere day={shown} who={who} everyone={everyone} />
            </>
          ) : (
            <span className="grow muted">{t('Nothing entered')}</span>
          )}
          {isClosingDay(picked, closing) && (
            <span className="cal-closing small row-note">
              <span className="cal-flag" aria-hidden="true" /> {t('Hours due (締め申請通知)')}
            </span>
          )}
        </div>
      )}
    </div>
  );
}
