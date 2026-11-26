// The list: places you've been (when you went, latest first) and places you want to go,
// all of them or one category's, newest, nearest, best rated or A–Z first, as a
// list or on a map.
import { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { api, errorMessage, type Restaurant } from '../../api';
import { useCached } from '../../cache';
import { Skeleton } from '../../components/Overlay';
import { t } from '../../i18n';
import { locale } from '../../language';
import { href } from '../../router';
import { personTone, useSpace } from '../../space';
import { categories, categoryName, categoryOf, categoryShort, CategoryTile, Icon, type Category } from './categories';
import { shortDate } from './format';
import { byDistance, distanceText, useLocation } from './nearby';
import { PlacesMap } from './PlacesMap';
import { ErrorText } from '../../components/ErrorText';

export type Tab = 'been' | 'want';

const filterKey = 'places.category';
const sortKey = 'places.sort';
const viewKey = 'places.view';

/** The ways to order the list, in the menu's order. */
const sorts = ['newest', 'nearby', 'rated', 'name'] as const;
type Sort = (typeof sorts)[number];

function sortName(s: Sort): string {
  switch (s) {
    case 'newest':
      return t('Newest');
    case 'nearby':
      return t('Nearby');
    case 'rated':
      return t('Top rated');
    case 'name':
      return t('A–Z');
  }
}

/** The average of everyone's stars; null when nobody has rated it. */
function averageStars(r: Restaurant): number | null {
  if (r.ratings.length === 0) return null;
  return r.ratings.reduce((sum, x) => sum + x.stars, 0) / r.ratings.length;
}

/** Best rated first, the unrated last; ties keep the newest-first order they came in. */
function byRating(places: Restaurant[]): Restaurant[] {
  return [...places].sort((a, b) => (averageStars(b) ?? -1) - (averageStars(a) ?? -1));
}

/** By name, as the language sorts them (Japanese names by their reading order). */
function byName(places: Restaurant[]): Restaurant[] {
  const collator = new Intl.Collator(locale(), { numeric: true, sensitivity: 'base' });
  return [...places].sort((a, b) => collator.compare(a.name, b.name));
}

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
  const [searchOpen, setSearchOpen] = useState(false);
  const [filter, setFilterState] = useState<Category | 'all'>(savedFilter);
  const { space } = useSpace();
  const setFilter = (c: Category | 'all') => {
    setFilterState(c);
    saveFilter(c);
  };
  // How the list is ordered; a list or a map. Both remembered on this device.
  const [sort, setSortState] = useState<Sort>(() => remembered(sortKey, sorts, 'newest'));
  const [view, setViewState] = useState(() => remembered(viewKey, ['list', 'map'] as const, 'list'));
  const location = useLocation();
  const { data: map } = useCached('map-key', api.mapKey, errorMessage);
  const mapKey = map?.key ?? '';
  const setSort = (s: Sort) => {
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
  const shown = near ? byDistance(near, filtered) : sort === 'rated' ? byRating(filtered) : sort === 'name' ? byName(filtered) : filtered;
  const onMap = view === 'map' && mapKey !== '';
  // Search is a button until it's wanted; open while there's something typed.
  const searchable = (list?.length ?? 0) > 4;
  const searching = searchable && (searchOpen || query !== '');

  return (
    <section className="stack">
      <header className="page-head">
        <div className="row spread">
          <h1 className="display">{t('Our places')}</h1>
          <a className="button add-wide" href={addHref}>
            <Icon name="add" size={20} />
            {t('Add a place')}
          </a>
        </div>
        <nav className="tabs-seg" aria-label={t('Lists')}>
          <a className={tab === 'been' ? 'active' : ''} href={href('home')} aria-current={tab === 'been' ? 'page' : undefined}>
            {t('Been there')} <span className="count">{list ? been.length : ''}</span>
          </a>
          <a className={tab === 'want' ? 'active' : ''} href={href('want')} aria-current={tab === 'want' ? 'page' : undefined}>
            {t('Want to go')} <span className="count">{list ? want.length : ''}</span>
          </a>
        </nav>
        {list && list.length > 0 && (
          <div className="toolbar">
            {showFilter ? (
              <CategoryMenu value={activeFilter} total={onTab.length} counts={counts} onChange={setFilter} />
            ) : (
              <span />
            )}
            <span className="toolbar-icons">
              {searchable && (
                <button
                  type="button"
                  className={`icon-toggle${searching ? ' on' : ''}`}
                  aria-label={t('Search')}
                  aria-pressed={searching}
                  onClick={() => {
                    if (searching) setQuery('');
                    setSearchOpen(!searching);
                  }}
                >
                  <Icon name="search" size={20} />
                </button>
              )}
              <SortMenu value={sort} onChange={setSort} />
              {mapKey && (
                <button
                  type="button"
                  className={`icon-toggle${onMap ? ' on' : ''}`}
                  aria-label={t('Map')}
                  aria-pressed={onMap}
                  onClick={() => setView(onMap ? 'list' : 'map')}
                >
                  <Icon name="map" size={20} />
                </button>
              )}
            </span>
          </div>
        )}
        {searching && (
          <input
            type="search"
            className="search"
            aria-label={t('Search')}
            placeholder={t('Search by name, area, kind or notes')}
            value={query}
            autoFocus
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                setQuery('');
                setSearchOpen(false);
              }
            }}
          />
        )}
        {sort === 'nearby' && location.state.status === 'asking' && <p className="hint">{t('Finding where you are…')}</p>}
        {sort === 'nearby' && location.state.status === 'failed' && (
          <p className="hint">
            {location.state.denied
              ? t('Location is off for this site, so the list is newest first. Allow it in the browser to sort by distance.')
              : t('Couldn’t find where you are, so the list is newest first.')}
          </p>
        )}
      </header>

      <ErrorText message={error} />
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
                <CategoryTile category={categoryOf(r.category)} cover={r.cover} />
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
                </span>
                {near && distanceText(near, r) ? (
                  <span className="spot-when">
                    <strong className="distance">{distanceText(near, r)}</strong>
                    {r.last_visit && <span>{shortDate(r.last_visit)}</span>}
                    {r.visit_count > 1 && <span>{t('{n}×', { n: r.visit_count })}</span>}
                  </span>
                ) : (
                  r.last_visit && (
                    <span className="spot-when">
                      <span>{shortDate(r.last_visit)}</span>
                      {r.visit_count > 1 && <span>{t('{n}×', { n: r.visit_count })}</span>}
                    </span>
                  )
                )}
              </a>
            </li>
          ))}
        </ul>
      )}

      <a className="fab" href={addHref}>
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
        {t('Add a place')}
      </a>
    </section>
  );
}

function matches(r: Restaurant, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  // A visit's note too: "where did we have that?"
  return [r.name, r.area, r.cuisine, r.notes, ...(r.visit_notes ?? []).map((v) => v.note)].some((s) => s.toLowerCase().includes(q));
}

/** How to order the list: an icon button, the same whichever order (it's a menu, not a toggle), opening a menu of the ways with the current one ticked. */
function SortMenu({ value, onChange }: { value: Sort; onChange: (s: Sort) => void }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useDismiss(open, box, () => setOpen(false));
  return (
    <div className="cat-menu" ref={box}>
      <button
        type="button"
        className="icon-toggle"
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={t('Order: {name}', { name: sortName(value) })}
        onClick={() => setOpen(!open)}
      >
        <Icon name="sort" size={20} />
      </button>
      {open && (
        <div className="cat-menu-list end" role="radiogroup" aria-label={t('Order')}>
          {sorts.map((s) => (
            <button
              key={s}
              type="button"
              role="radio"
              aria-checked={value === s}
              onClick={() => {
                onChange(s);
                setOpen(false);
              }}
            >
              <span className="grow">{sortName(s)}</span>
              {value === s && <Icon name="check" size={18} />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** While open, a tap outside box or Escape calls close. */
function useDismiss(open: boolean, box: RefObject<HTMLDivElement | null>, close: () => void) {
  useEffect(() => {
    if (!open) return;
    const away = (e: Event) => box.current && !box.current.contains(e.target as Node) && close();
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && close();
    document.addEventListener('pointerdown', away);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('pointerdown', away);
      document.removeEventListener('keydown', esc);
    };
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps
}

/**
 * The category to show (.cat-menu): a button saying which ("All places 5",
 * "Nature 1"), opening a menu of the categories with places on this list.
 */
function CategoryMenu({
  value,
  total,
  counts,
  onChange,
}: {
  value: Category | 'all';
  total: number;
  counts: Map<Category, number>;
  onChange: (c: Category | 'all') => void;
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  // Closes on a tap elsewhere, or Escape.
  useDismiss(open, box, () => setOpen(false));
  const pick = (c: Category | 'all') => {
    onChange(c);
    setOpen(false);
  };
  const label = value === 'all' ? t('All places') : categoryName(value);
  return (
    <div className="cat-menu" ref={box}>
      <button
        type="button"
        className={`cat-menu-button${value !== 'all' ? ' on' : ''}`}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={t('Category: {name}', { name: label })}
        onClick={() => setOpen(!open)}
      >
        <Icon name={value === 'all' ? 'all' : value} size={17} />
        {value === 'all' ? t('All places') : categoryShort(value)}
        <span className="count">{value === 'all' ? total : (counts.get(value) ?? 0)}</span>
        <Icon name="down" size={16} />
      </button>
      {open && (
        <div className="cat-menu-list" role="radiogroup" aria-label={t('Category')}>
          <button type="button" role="radio" aria-checked={value === 'all'} onClick={() => pick('all')}>
            <Icon name="all" size={18} />
            <span className="grow">{t('All places')}</span>
            <span className="count">{total}</span>
          </button>
          {categories
            .filter((c) => (counts.get(c) ?? 0) > 0 || c === value)
            .map((c) => (
              <button key={c} type="button" role="radio" aria-checked={value === c} onClick={() => pick(c)}>
                <CategoryTile category={c} size="sm" />
                <span className="grow">{categoryName(c)}</span>
                <span className="count">{counts.get(c) ?? 0}</span>
              </button>
            ))}
        </div>
      )}
    </div>
  );
}
