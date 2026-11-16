// The list: places you've been (latest visit first) and places you want to go.
import { useMemo, useState } from 'react';
import { api, errorMessage, type Restaurant } from '../../api';
import { useCached } from '../../cache';
import { Skeleton } from '../../components/Overlay';
import { t, tn } from '../../i18n';
import { href } from '../../router';
import { Avatar, personTone, useSpace } from '../../space';
import { shortDate, tileColor, today } from './format';

export type Tab = 'been' | 'want';

export function RestaurantsPage({ tab }: { tab: Tab }) {
  const { data: list, error } = useCached('restaurants', api.restaurants, errorMessage);
  const [query, setQuery] = useState('');
  const { space } = useSpace();

  const been = useMemo(
    () =>
      (list ?? [])
        .filter((r) => r.visit_count > 0)
        .sort((a, b) => (b.last_visit ?? '').localeCompare(a.last_visit ?? '') || b.id - a.id),
    [list],
  );
  const want = useMemo(() => (list ?? []).filter((r) => r.visit_count === 0).sort((a, b) => b.id - a.id), [list]);
  const month = today().slice(0, 7);
  const thisMonth = been.filter((r) => r.last_visit?.startsWith(month)).length;
  const addHref = href('add', tab === 'want' ? { want: 1 } : undefined);
  const shown = (tab === 'been' ? been : want).filter((r) => matches(r, query));

  return (
    <section className="stack">
      <header className="page-head">
        <div className="row spread">
          <div>
            <h1 className="display">{t('Our spots')}</h1>
            {list && list.length > 0 && (
              <p className="subtitle">
                {tn(list.length, '{n} place', '{n} places')}
                {thisMonth > 0 && <> · {t('{n} this month', { n: thisMonth })}</>}
              </p>
            )}
          </div>
          <span className="row">
            {space && (
              <span className="avatars">
                {space.members.map((m, i) => (
                  <Avatar key={m.email} name={m.name} tone={i === 1 ? 'b' : 'a'} />
                ))}
              </span>
            )}
            <a className="button add-wide" href={addHref}>
              + {t('Add a place')}
            </a>
          </span>
        </div>
        <nav className="tabs-seg" aria-label={t('Lists')}>
          <a className={tab === 'been' ? 'active' : ''} href={href('home')} aria-current={tab === 'been' ? 'page' : undefined}>
            {t('Been there')} <span className="count">{list ? been.length : ''}</span>
          </a>
          <a className={tab === 'want' ? 'active' : ''} href={href('want')} aria-current={tab === 'want' ? 'page' : undefined}>
            {t('Want to go')} <span className="count">{list ? want.length : ''}</span>
          </a>
        </nav>
        {list && list.length > 4 && (
          <input
            type="search"
            className="search"
            aria-label={t('Search')}
            placeholder={t('Search by name, area, food or notes')}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        )}
      </header>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {list === null && !error && <Skeleton rows={4} />}
      {list !== null && shown.length === 0 && (
        <div className="empty">
          {query ? (
            <p>{t('Nothing matches “{query}”.', { query })}</p>
          ) : tab === 'been' ? (
            <>
              <p className="empty-title">{t('No places yet')}</p>
              <p className="muted">{t('Add the first place you ate together.')}</p>
            </>
          ) : (
            <>
              <p className="empty-title">{t('Nothing on the list')}</p>
              <p className="muted">{t('Save places you’d like to try, so you have ideas for next time.')}</p>
            </>
          )}
        </div>
      )}
      {shown.length > 0 && (
        <ul className="cards" aria-label={tab === 'been' ? t('Been there') : t('Want to go')}>
          {shown.map((r) => (
            <li key={r.id}>
              <a className="spot" href={href('restaurant', { id: r.id })}>
                <span className={`tile ${tileColor(r.id)}`} aria-hidden="true">
                  {(r.name.trim()[0] ?? '?').toUpperCase()}
                </span>
                <span className="spot-body">
                  <span className="spot-name">{r.name}</span>
                  <span className="spot-line">
                    {r.ratings.map((x) => (
                      <span
                        key={x.name}
                        className={`chip ${personTone(space, x.name)}`}
                        title={t('{name}: {n} stars', { name: x.name, n: x.stars })}
                      >
                        {(x.name.trim()[0] ?? '?').toUpperCase()} ★{x.stars}
                      </span>
                    ))}
                    {(r.cuisine || r.area) && <span className="spot-meta">{r.cuisine || r.area}</span>}
                    {tab === 'want' && r.added_by && (
                      <span className="spot-meta">· {t('Added by {name}', { name: r.added_by })}</span>
                    )}
                  </span>
                  {noteMatch(r, query) && (
                    <span className="spot-note">
                      {shortDate(noteMatch(r, query)!.visited_on)} · {noteMatch(r, query)!.note}
                    </span>
                  )}
                </span>
                {r.last_visit && (
                  <span className="spot-when">
                    <strong>{shortDate(r.last_visit)}</strong>
                    <span>{tn(r.visit_count, '{n} visit', '{n} visits')}</span>
                  </span>
                )}
              </a>
            </li>
          ))}
        </ul>
      )}

      <a className="fab" href={addHref} aria-label={t('Add a place')}>
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
      </a>
    </section>
  );
}

function matches(r: Restaurant, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [r.name, r.area, r.cuisine, r.notes].some((s) => s.toLowerCase().includes(q)) || !!noteMatch(r, query);
}

/** The newest visit whose note has the search in it: "where did we have that?". */
function noteMatch(r: Restaurant, query: string) {
  const q = query.trim().toLowerCase();
  if (!q) return undefined;
  return r.visit_notes?.find((v) => v.note.toLowerCase().includes(q));
}
