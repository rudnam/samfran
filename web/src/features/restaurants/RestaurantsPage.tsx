// The list: places you've been (latest visit first) and places you want to go,
// all of them or one category's, newest or nearest first, as a list or on a map.
import { useEffect, useMemo, useState } from 'react';
import { api, errorMessage, type Restaurant } from '../../api';
import { useCached } from '../../cache';
import { Skeleton } from '../../components/Overlay';
import { t, tn } from '../../i18n';
import { href } from '../../router';
import { Avatar, personTone, useSpace } from '../../space';
import { categories, categoryOf, categoryShort, CategoryTile, Icon, type Category } from './categories';
import { shortDate, today } from './format';
import { byDistance, distanceText, useLocation } from './nearby';
import { PlacesMap } from './PlacesMap';

export type Tab = 'been' | 'want';

const filterKey = 'places.category';
const sortKey = 'places.sort';
const viewKey = 'places.view';

/** A choice remembered on this device; def when there's none, or storage is off. */
function remembered<T extends string>(key: string, options: readonly T[], def: T): T {
  try {
    const v = localStorage.getItem(key);
    return v && (options as readonly string[]).includes(v) ? (v as T) : def;
  } catch {
    return def;
  }
}

function remember(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Private browsing: it just isn't remembered.
  }
}

/** The category last filtered by on this device, so the list opens on it. */
function savedFilter(): Category | 'all' {
  try {
    const v = localStorage.getItem(filterKey);
    return v && (categories as readonly string[]).includes(v) ? (v as Category) : 'all';
  } catch {
    return 'all';
  }
}

function saveFilter(c: Category | 'all') {
  try {
    if (c === 'all') localStorage.removeItem(filterKey);
    else localStorage.setItem(filterKey, c);
  } catch {
    // Private browsing: it just isn't remembered.
  }
}

export function RestaurantsPage({ tab }: { tab: Tab }) {
  const { data: list, error } = useCached('restaurants', api.restaurants, errorMessage);
  const [query, setQuery] = useState('');
  const [filter, setFilterState] = useState<Category | 'all'>(savedFilter);
  const { space } = useSpace();
  const setFilter = (c: Category | 'all') => {
    setFilterState(c);
    saveFilter(c);
  };
  // Newest or nearest first; a list or a map. Both remembered on this device.
  const [sort, setSortState] = useState(() => remembered(sortKey, ['newest', 'nearby'] as const, 'newest'));
  const [view, setViewState] = useState(() => remembered(viewKey, ['list', 'map'] as const, 'list'));
  const location = useLocation();
  const { data: map } = useCached('map-key', api.mapKey, errorMessage);
  const mapKey = map?.key ?? '';
  const setSort = (s: 'newest' | 'nearby') => {
    setSortState(s);
    remember(sortKey, s);
    if (s === 'nearby') location.ask();
  };
  const setView = (v: 'list' | 'map') => {
    setViewState(v);
    remember(viewKey, v);
  };
  // Opened on Nearby: ask where they are (a browser that was allowed before doesn't ask again).
  useEffect(() => {
    if (sort === 'nearby') location.ask();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const here = location.state.status === 'here' ? location.state.spot : null;

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
  const onTab = tab === 'been' ? been : want;
  // The chips: only once there's more than one category to tell apart.
  const inUse = new Set((list ?? []).map((r) => categoryOf(r.category)));
  const showFilter = inUse.size > 1 || (filter !== 'all' && inUse.size > 0);
  const counts = new Map<Category, number>();
  for (const r of onTab) counts.set(categoryOf(r.category), (counts.get(categoryOf(r.category)) ?? 0) + 1);
  const activeFilter = showFilter ? filter : 'all';
  const filtered = onTab.filter((r) => (activeFilter === 'all' || categoryOf(r.category) === activeFilter) && matches(r, query));
  // Where they are, when the list is nearest first.
  const near = sort === 'nearby' ? here : null;
  const shown = near ? byDistance(near, filtered) : filtered;
  const onMap = view === 'map' && mapKey !== '';

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
        {showFilter && (
          <div className="cat-filter" role="radiogroup" aria-label={t('Category')}>
            <button type="button" role="radio" aria-checked={activeFilter === 'all'} className={activeFilter === 'all' ? 'on' : ''} onClick={() => setFilter('all')}>
              <Icon name="all" size={17} />
              {t('All')} <span className="count">{onTab.length}</span>
            </button>
            {categories
              .filter((c) => (counts.get(c) ?? 0) > 0 || c === activeFilter)
              .map((c) => (
                <button key={c} type="button" role="radio" aria-checked={activeFilter === c} className={activeFilter === c ? 'on' : ''} onClick={() => setFilter(c)}>
                  <Icon name={c} size={17} />
                  {categoryShort(c)} <span className="count">{counts.get(c) ?? 0}</span>
                </button>
              ))}
          </div>
        )}
        {list && list.length > 0 && (
          <div className="row spread list-tools">
            <div className="pill-seg" role="radiogroup" aria-label={t('Order')}>
              <button type="button" role="radio" aria-checked={sort === 'newest'} className={sort === 'newest' ? 'on' : ''} onClick={() => setSort('newest')}>
                {tab === 'been' ? t('Latest') : t('Newest')}
              </button>
              <button type="button" role="radio" aria-checked={sort === 'nearby'} className={sort === 'nearby' ? 'on' : ''} onClick={() => setSort('nearby')}>
                <Icon name="nearby" size={15} />
                {t('Nearby')}
              </button>
            </div>
            {mapKey && (
              <div className="pill-seg" role="radiogroup" aria-label={t('View')}>
                <button type="button" role="radio" aria-checked={!onMap} className={!onMap ? 'on' : ''} onClick={() => setView('list')}>
                  <Icon name="list" size={15} />
                  {t('List')}
                </button>
                <button type="button" role="radio" aria-checked={onMap} className={onMap ? 'on' : ''} onClick={() => setView('map')}>
                  <Icon name="map" size={15} />
                  {t('Map')}
                </button>
              </div>
            )}
          </div>
        )}
        {sort === 'nearby' && location.state.status === 'asking' && <p className="hint">{t('Finding where you are…')}</p>}
        {sort === 'nearby' && location.state.status === 'failed' && (
          <p className="hint">
            {location.state.denied
              ? t('Location is off for this site, so the list is newest first. Allow it in the browser to sort by distance.')
              : t('Couldn’t find where you are, so the list is newest first.')}
          </p>
        )}
        {list && list.length > 4 && (
          <input
            type="search"
            className="search"
            aria-label={t('Search')}
            placeholder={t('Search by name, area, kind or notes')}
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
          ) : activeFilter !== 'all' && onTab.length > 0 ? (
            <>
              <p>{t('Nothing in {category} here.', { category: categoryShort(activeFilter) })}</p>
              <button type="button" className="link" onClick={() => setFilter('all')}>
                {t('Show all')}
              </button>
            </>
          ) : tab === 'been' ? (
            <>
              <p className="empty-title">{t('No places yet')}</p>
              <p className="muted">{t('Add the first place you went to together.')}</p>
            </>
          ) : (
            <>
              <p className="empty-title">{t('Nothing on the list')}</p>
              <p className="muted">{t('Save places you’d like to go, so you have ideas for next time.')}</p>
            </>
          )}
        </div>
      )}
      {onMap && shown.length > 0 && <PlacesMap places={shown} mapKey={mapKey} me={near} />}
      {!onMap && shown.length > 0 && (
        <ul className="cards" aria-label={tab === 'been' ? t('Been there') : t('Want to go')}>
          {shown.map((r) => (
            <li key={r.id}>
              <a className="spot" href={href('restaurant', { id: r.id })}>
                <CategoryTile category={categoryOf(r.category)} />
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
                {near && distanceText(near, r) ? (
                  <span className="spot-when">
                    <strong className="distance">{distanceText(near, r)}</strong>
                    {r.last_visit && <span>{shortDate(r.last_visit)}</span>}
                  </span>
                ) : (
                  r.last_visit && (
                    <span className="spot-when">
                      <strong>{shortDate(r.last_visit)}</strong>
                      <span>{tn(r.visit_count, '{n} visit', '{n} visits')}</span>
                    </span>
                  )
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
