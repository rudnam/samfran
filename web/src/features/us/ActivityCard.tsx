// Recent changes: who changed what you share (shifts and their settings,
// the calendar link, restaurants, invitations), from the app or via Claude.
import { useEffect, useState } from 'react';
import { api, errorMessage, type ActivityEntry } from '../../api';
import { t } from '../../i18n';
import { locale } from '../../language';
import { monthName } from '../shifts/codes';

const setting: Record<string, () => string> = {
  person_name: () => t('whose shifts'),
  name_on_sheet: () => t('the name on the sheet'),
  time_zone: () => t('the time zone'),
};

/** What happened, worded; '' for a kind this version doesn't know. */
function describe(e: ActivityEntry): string {
  const d = e.details;
  const name = String(d.name ?? '');
  switch (e.kind) {
    case 'shifts_saved':
      return t('saved the shifts for {month}', { month: monthName(String(d.month)) });
    case 'shift_settings': {
      const parts: string[] = [];
      const lengths = (d.lengths ?? {}) as Record<string, [number | null, number | null]>;
      for (const [letter, [from, to]] of Object.entries(lengths).sort()) {
        parts.push(
          to === null
            ? t('removed {letter}', { letter })
            : from
              ? t('set {letter} to {to} hours (was {from})', { letter, to, from })
              : t('set {letter} to {to} hours', { letter, to }),
        );
      }
      for (const key of Object.keys(setting)) {
        const change = d[key] as [string, string] | undefined;
        if (change) parts.push(t('changed {what} to “{to}”', { what: setting[key](), to: change[1] }));
      }
      return parts.join(', ');
    }
    case 'calendar_link_new':
      return t('made a new calendar link');
    case 'calendar_link_off':
      return t('turned off the calendar link');
    case 'restaurant_added':
      return d.been ? t('added {name} (been there)', { name }) : t('added {name} to want to go', { name });
    case 'restaurant_edited':
      return t('edited {name}', { name });
    case 'restaurant_deleted':
      return t('deleted {name}', { name });
    case 'visit_logged':
      return t('logged a visit to {name}', { name });
    case 'visit_removed':
      return t('removed a visit to {name}', { name });
    case 'rated':
      return Number(d.stars) > 0 ? t('rated {name} ★{stars}', { name, stars: Number(d.stars) }) : t('cleared their rating of {name}', { name });
    case 'invited':
      return t('invited {email}', { email: String(d.email) });
    case 'invite_cancelled':
      return t('cancelled the invitation for {email}', { email: String(d.email) });
  }
  return '';
}

export function ActivityCard() {
  const [list, setList] = useState<ActivityEntry[] | null>(null);
  const [error, setError] = useState('');
  const [all, setAll] = useState(false);

  useEffect(() => {
    api.activity().then(setList, (err) => setError(errorMessage(err)));
  }, []);

  const shown = (list ?? []).map((e) => ({ e, text: describe(e) })).filter((x) => x.text);
  return (
    <div className="card stack">
      <h2>{t('Recent changes')}</h2>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {list && shown.length === 0 && <p className="muted">{t('Nothing yet. Changes either of you make show up here.')}</p>}
      {shown.length > 0 && (
        <ul className="activity" aria-label={t('Recent changes')}>
          {(all ? shown : shown.slice(0, 8)).map(({ e, text }) => (
            <li key={e.id}>
              <span className="grow">
                <strong>{e.me ? t('You') : e.who || t('Someone')}</strong> {text}
                {e.via === 'claude' && <span className="via"> · {t('via Claude')}</span>}
              </span>
              <time className="muted small" dateTime={e.at}>
                {new Date(e.at).toLocaleString(locale(), { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })}
              </time>
            </li>
          ))}
        </ul>
      )}
      {!all && shown.length > 8 && (
        <button type="button" className="link" onClick={() => setAll(true)}>
          {t('Show more')}
        </button>
      )}
    </div>
  );
}
