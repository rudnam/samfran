// Who's at work with Sam on a day: nobody is shown until the people button
// beside Calendar/List is on. Then everyone there during her shift, by when
// they start, each with a bar of when they're there.
import type { ShiftDay } from '../../api';
import { t } from '../../i18n';
import { WithNames } from './WithNames';

/** "9:00" from an ISO time with its offset, as written. */
function hm(iso?: string): string {
  const m = iso?.match(/T(\d\d):(\d\d)/);
  return m ? `${Number(m[1])}:${m[2]}` : '';
}

/** The toggle beside Calendar/List: a small people icon. */
export function EveryoneToggle({ on, onChange }: { on: boolean; onChange: (on: boolean) => void }) {
  return (
    <button
      type="button"
      className={`icon-toggle${on ? ' on' : ''}`}
      aria-pressed={on}
      aria-label={t('Everyone there')}
      title={t('Everyone there')}
      onClick={() => onChange(!on)}
    >
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <circle cx="9" cy="8" r="3.5" />
        <circle cx="17" cy="9" r="2.5" />
        <path d="M3 20c0-3.5 2.7-6 6-6s6 2.5 6 6M15 14.5c3 0 6 1.8 6 5.5" />
      </svg>
    </button>
  );
}

export function WhoThere({
  day,
  who,
  everyone,
  className = 'with small row-note',
}: {
  day: ShiftDay;
  /** Sam's name, for her own row. */
  who: string;
  everyone: boolean;
  className?: string;
}) {
  const others = day.others ?? [];
  if (!everyone) return null;
  // No times to draw bars against (an unknown code): just the names.
  if (!day.start || !day.end) {
    const names = [...(day.with ?? []), ...others.map((o) => o.name)];
    return names.length ? <WithNames names={names} className={className} /> : null;
  }

  // Bars against Sam's shift: 0 at her start, 1 at her end.
  const from = Date.parse(day.start);
  const span = Date.parse(day.end) - from;
  const at = (iso?: string, fallback = 0) => (iso ? Math.min(1, Math.max(0, (Date.parse(iso) - from) / span)) : fallback);
  type Row = { name: string; start?: string; end?: string; kind: 'me' | 'same' | 'other' };
  const rows: Row[] = [
    { name: who, start: day.start, end: day.end, kind: 'me' } as Row,
    ...(day.with ?? []).map((name): Row => ({ name, start: day.start, end: day.end, kind: 'same' })),
    ...others.map(
      (o): Row => ({
        name: o.name,
        start: o.start,
        end: o.end,
        kind: 'other',
      }),
    ),
  ].sort((a, b) => Date.parse(a.start ?? '') - Date.parse(b.start ?? '') || (a.kind === 'me' ? -1 : b.kind === 'me' ? 1 : 0));

  return (
    <ul className="there row-note" aria-label={t('Everyone there')}>
      {rows.map((r) => {
        const left = at(r.start, 0);
        const width = Math.max(0.04, at(r.end, 1) - left);
        return (
          <li key={`${r.kind}:${r.name}`} className={r.kind}>
            <span className="there-name">
              {r.name}
            </span>
            <span className="there-bar" aria-hidden="true">
              <i style={{ left: `${left * 100}%`, width: `${width * 100}%` }} />
            </span>
            <span className="there-time">
              {hm(r.start)}–{hm(r.end)}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
