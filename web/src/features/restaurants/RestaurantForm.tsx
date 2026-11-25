// Adding a place (been there, or want to go) and editing one.
import { BackLink } from '../../components/BackLink';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, errorMessage, type FoundPlace, type Restaurant, type RestaurantFields } from '../../api';
import { StarPicker } from '../../components/Stars';
import { t } from '../../i18n';
import { href, navigate, replaceRoute } from '../../router';
import { isMapsLink, parseShared } from '../../share';
import { categories, categoryHint, categoryName, categoryOf, CategoryTile, Icon, kindPlaceholder } from './categories';
import { today } from './format';
import { metersText, whereIfAllowed, type Spot } from './nearby';
import { findSaved, type SavedMatch } from './saved';
import { ErrorText } from '../../components/ErrorText';
import { Skeleton } from '../../components/Overlay';

/**
 * Places matching what's typed in the name field, as it's typed: a quarter
 * second after the last key, two characters or more. null until searched.
 */
function usePlaceSearch(query: string, session: string, near: { lat: number; lng: number } | null) {
  const [found, setFound] = useState<FoundPlace[] | null>(null);
  const [failed, setFailed] = useState(false);
  const latest = useRef(0);
  useEffect(() => {
    const q = query.trim();
    if ([...q].length < 2) {
      latest.current++;
      setFound(null);
      return;
    }
    const n = ++latest.current;
    const timer = setTimeout(() => {
      api.suggestPlaces(q, session, near ?? undefined).then(
        (f) => {
          if (n !== latest.current) return; // a newer search is on its way
          setFound(f);
          setFailed(false);
        },
        () => n === latest.current && setFailed(true),
      );
    }, 250);
    return () => clearTimeout(timer);
  }, [query]); // eslint-disable-line react-hooks/exhaustive-deps
  return { found, failed };
}

/**
 * What the Kind field says for OpenStreetMap's kind of place, when the kind
 * says something. Empty for a plain restaurant: OpenStreetMap doesn't say
 * which kind of food it serves, and the search doesn't return that.
 */
function kindName(kind: string): string {
  switch (kind) {
    case 'cafe':
      return t('Café');
    case 'bar':
      return t('Bar');
    case 'pub':
      return t('Pub');
    case 'biergarten':
      return t('Beer garden');
    case 'fast_food':
      return t('Fast food');
    case 'food_court':
      return t('Food court');
    case 'ice_cream':
      return t('Ice cream');
    case 'bakery':
      return t('Bakery');
    case 'pastry':
      return t('Pastries');
    case 'confectionery':
      return t('Sweets');
    case 'park':
      return t('Park');
    case 'garden':
      return t('Garden');
    case 'nature_reserve':
      return t('Nature reserve');
    case 'beach':
      return t('Beach');
    case 'peak':
      return t('Mountain');
    case 'viewpoint':
      return t('Viewpoint');
    case 'museum':
      return t('Museum');
    case 'gallery':
      return t('Gallery');
    case 'castle':
      return t('Castle');
    case 'zoo':
      return t('Zoo');
    case 'aquarium':
      return t('Aquarium');
    case 'theme_park':
      return t('Theme park');
    case 'cinema':
      return t('Cinema');
    case 'public_bath':
      return t('Bathhouse');
    case 'hot_spring':
      return t('Onsen');
    case 'mall':
      return t('Shopping mall');
    case 'marketplace':
      return t('Market');
    default:
      return '';
  }
}

/**
 * The picked place on Google Maps: its own link, from Google, or Maps
 * searching for the name around where it is, which opens on that branch, not the chain.
 */
function pickedMapURL(p: FoundPlace): string {
  if (p.map_url) return p.map_url;
  return `https://www.google.com/maps/search/${encodeURIComponent(p.name)}/@${p.lat},${p.lng},17z`;
}

/** A Google session token: 32 random hex digits (crypto.randomUUID needs https). */
function newSession(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
}

const empty: RestaurantFields = { name: '', area: '', cuisine: '', category: 'eat', map_url: '', notes: '' };

/**
 * id edits that restaurant; without it, adds one. want starts on the
 * want-to-go side. shared is text shared from Google Maps, to fill it in.
 */
export function RestaurantForm({ id, want, shared }: { id?: number; want?: boolean; shared?: string }) {
  // The place's name as saved, for the way back while editing.
  const [savedName, setSavedName] = useState('');
  const [fields, setFields] = useState<RestaurantFields | null>(() => {
    if (id) return null;
    const s = shared ? parseShared(shared) : null;
    return s ? { ...empty, name: s.name, map_url: s.url } : empty;
  });
  const [lookingUp, setLookingUp] = useState(false);
  const [canPaste, setCanPaste] = useState(() => typeof navigator !== 'undefined' && !!navigator.clipboard?.readText);
  const [been, setBeen] = useState(!want);
  const [visitedOn, setVisitedOn] = useState(today());
  const [stars, setStars] = useState(0);
  // A category picked by hand stays; until then, the picked place sets it.
  const [categoryPicked, setCategoryPicked] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // Searching by name, when adding: what's typed, until a place is picked.
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<FoundPlace | null>(null);
  // Near where they are first, if the site may know (they're often adding the
  // place they're at); else near the bus stop, if there is one: likely the right town.
  const [here, setHere] = useState<Spot | null>(null);
  const [stop, setStop] = useState<Spot | null>(null);
  const near = here ?? stop;
  // What's already saved, to say so instead of adding it twice.
  const [saved, setSaved] = useState<Restaurant[]>([]);
  // One Google session from typing to picking; a new one after each pick.
  const [session, setSession] = useState(() => newSession());
  const search = usePlaceSearch(query, session, near);

  useEffect(() => {
    if (id) return;
    api.busStop().then(
      (s) => s.set && setStop({ lat: s.lat, lng: s.lng }),
      () => {},
    );
    void whereIfAllowed().then(setHere);
    api.restaurants().then(setSaved, () => {});
  }, [id]);

  /** A suggestion's saved twin: the same place only when it surely is (same name, same spot). */
  const savedFound = (p: FoundPlace): SavedMatch => findSaved(saved, p.name, p.place_id && !p.lat && !p.lng ? { street: p.street } : p, true);
  /** What's in the form, saved already? The picked place by where it is; a name typed in, by name (and area, if typed). */
  const savedTyped = (): SavedMatch =>
    !fields
      ? {}
      : picked && picked.name.trim() === fields.name.trim()
        ? findSaved(saved, fields.name, picked, true)
        : findSaved(saved, fields.name, { area: fields.area }, false);

  /** Fills in the form from a place with its details. Area and Food only when empty: what they typed stays. */
  function fillFrom(p: FoundPlace, food: string) {
    setFields((f) =>
      f
        ? {
            ...f,
            name: p.name,
            area: f.area.trim() ? f.area : p.area,
            cuisine: f.cuisine.trim() ? f.cuisine : food,
            category: categoryPicked || !p.category ? f.category : categoryOf(p.category),
          }
        : f,
    );
    setPicked(p);
  }

  async function pick(p: FoundPlace) {
    setQuery('');
    if (!p.place_id) {
      fillFrom(p, kindName(p.kind));
      return;
    }
    // Google's suggestion has only a name: ask for the rest.
    setFields((f) => (f ? { ...f, name: p.name } : f));
    setPicked(null);
    try {
      const d = await api.placeDetails(p.place_id, session);
      fillFrom(d, d.food ?? '');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSession(newSession());
    }
  }

  useEffect(() => {
    if (!id) return;
    api.restaurant(id).then(
      (r) => {
        setSavedName(r.name);
        setFields({ name: r.name, area: r.area, cuisine: r.cuisine, category: categoryOf(r.category), map_url: r.map_url, notes: r.notes });
      },
      (err) => setError(errorMessage(err)),
    );
  }, [id]);

  function set<K extends keyof RestaurantFields>(key: K, value: string) {
    setFields((f) => (f ? { ...f, [key]: value } : f));
  }

  /** Fills in the name from a Maps link when it's still empty. */
  async function nameFromLink(url: string) {
    if (!isMapsLink(url) || fields?.name.trim()) return;
    setLookingUp(true);
    try {
      const { name } = await api.lookupMap(url);
      if (name) setFields((f) => (f && !f.name.trim() ? { ...f, name } : f));
    } catch {
      // Not found or offline: they can type it.
    } finally {
      setLookingUp(false);
    }
  }

  // Shared from Maps with only a link: ask the server for the name.
  useEffect(() => {
    if (!id && shared && fields && !fields.name && fields.map_url) void nameFromLink(fields.map_url);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /** A pasted "Name\nhttps://maps…" goes into both fields. */
  function pasteLink(text: string): boolean {
    const s = parseShared(text);
    if (!s.url || s.url === text.trim()) return false; // a plain link: let the field take it
    setFields((f) => (f ? { ...f, map_url: s.url, name: f.name.trim() ? f.name : s.name } : f));
    return true;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!fields) return;
    setBusy(true);
    setError('');
    try {
      const saved = id
        ? await api.updateRestaurant(id, fields)
        : await api.createRestaurant({
            ...fields,
            map_url: fields.map_url.trim() || (picked ? pickedMapURL(picked) : ''),
            // Where the picked place is (Google's suggestions have it only after their details).
            ...(picked && (picked.lat || picked.lng) ? { lat: picked.lat, lng: picked.lng } : {}),
            ...(been ? { visit: { visited_on: visitedOn }, stars: stars || undefined } : {}),
          });
      // Back goes to the list, not to this form.
      replaceRoute('restaurant', { id: saved.id });
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  /** Takes a link however it came (typed, pasted, the Paste button) and looks up its name. */
  function takeLink(text: string) {
    if (pasteLink(text)) return;
    const url = text.trim();
    set('map_url', url);
    if (isMapsLink(url)) void nameFromLink(url);
  }

  async function pasteFromClipboard() {
    try {
      takeLink(await navigator.clipboard.readText());
    } catch {
      setCanPaste(false); // not allowed here: they can paste into the field
    }
  }

  const mapField = fields && (
    <label className="field">
      {t('Google Maps link')}
      <span className="row">
        <input
          type="url"
          inputMode="url"
          placeholder="https://maps.app.goo.gl/…"
          value={fields.map_url}
          onChange={(e) => {
            // A link arriving whole (pasted, autofilled) is looked up now; typed, on blur.
            if (e.target.value.length - fields.map_url.length > 5) takeLink(e.target.value);
            else set('map_url', e.target.value);
          }}
          onBlur={(e) => void nameFromLink(e.target.value.trim())}
          onPaste={(e) => {
            if (pasteLink(e.clipboardData.getData('text'))) e.preventDefault();
          }}
        />
        {!id && canPaste && !fields.map_url && (
          <button type="button" className="secondary" onClick={pasteFromClipboard}>
            {t('Paste')}
          </button>
        )}
      </span>
      <span className="hint">
        {id
          ? t('Optional. Without one, “Open in Maps” searches for the name.')
          : lookingUp
            ? t('Looking up the place…')
            : picked
              ? t('Optional. The place you picked is already on the map.')
              : t('Optional. Paste one and the name fills itself in.')}
      </span>
    </label>
  );

  const back = id ? href('restaurant', { id }) : href(want ? 'want' : 'home');
  return (
    <section className="stack">
      {/* Editing goes back to the place, named as it's saved (not as it's being retyped). */}
      <BackLink href={back} label={id ? 'Back' : 'Our places'} text={id ? savedName || undefined : undefined} />
      <h1 className="display">{id ? t('Edit place') : t('Add a place')}</h1>
      {!fields && !error && <Skeleton rows={6} />}
      {fields && (
        <form className="card stack" onSubmit={submit}>
          {!id && (
            <div className="segmented" role="radiogroup" aria-label={t('Have you been?')}>
              <button type="button" role="radio" aria-checked={been} className={been ? 'on' : ''} onClick={() => setBeen(true)}>
                {t('Been there')}
              </button>
              <button type="button" role="radio" aria-checked={!been} className={!been ? 'on' : ''} onClick={() => setBeen(false)}>
                {t('Want to go')}
              </button>
            </div>
          )}
          <div className="field">
            <label htmlFor="place-name">{t('Name')}</label>
            <input
              id="place-name"
              required
              maxLength={200}
              autoComplete="off"
              value={fields.name}
              aria-describedby={id ? undefined : 'place-search-hint'}
              placeholder={lookingUp ? t('Looking up the place…') : id ? undefined : t('Type to search, e.g. すき家, 浜松城')}
              onChange={(e) => {
                set('name', e.target.value);
                if (!id) setQuery(e.target.value);
              }}
              autoFocus={!id && !shared}
            />
            {query && search.found && search.found.length > 0 && (
              <ul className="suggest" aria-label={t('Matching places')}>
                {search.found.map((p) => {
                  const { same: already, elsewhere } = savedFound(p);
                  const line = [p.street, p.area].filter(Boolean).join(' · ');
                  return (
                    <li key={p.place_id || `${p.name}${p.lat}${p.lng}`}>
                      {already ? (
                        <a className="suggest-row" href={href('restaurant', { id: already.id })}>
                          <CategoryTile category={categoryOf(p.category)} size="sm" />
                          <span className="suggest-text">
                            <strong>{p.name}</strong>
                            <span className="hint">{t('Already on your list')}</span>
                          </span>
                        </a>
                      ) : (
                        <button type="button" className="suggest-row" onClick={() => pick(p)}>
                          <CategoryTile category={categoryOf(p.category)} size="sm" />
                          <span className="suggest-text">
                            <strong>{p.name}</strong>
                            {line && <span className="hint">{line}</span>}
                            {elsewhere && (
                              <span className="hint">
                                {elsewhere.area
                                  ? t('You have one in {area}', { area: elsewhere.area })
                                  : t('You have one with this name')}
                              </span>
                            )}
                          </span>
                          {p.meters != null && <span className="suggest-meters">{metersText(p.meters)}</span>}
                        </button>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
            {!id && (
              <span className="hint" id="place-search-hint">
                {(() => {
                  const { same: dup, elsewhere } = savedTyped();
                  if (dup) {
                    return (
                      <>
                        {t('Already on your list:')} <a href={href('restaurant', { id: dup.id })}>{dup.name}</a>
                      </>
                    );
                  }
                  const another = elsewhere && (
                    <>
                      {' '}
                      {elsewhere.area ? t('You have another one, in {area}:', { area: elsewhere.area }) : t('You have another one:')}{' '}
                      <a href={href('restaurant', { id: elsewhere.id })}>{elsewhere.name}</a>
                    </>
                  );
                  if (picked && !query) {
                    return (
                      <>
                        ✓ {[picked.street, picked.area].filter(Boolean).join(' · ')}{' '}
                        <a href={pickedMapURL(picked)} target="_blank" rel="noreferrer">
                          {t('Check on the map')}
                        </a>{' '}
                        <button type="button" className="link" onClick={() => setPicked(null)}>
                          {t('Not this one')}
                        </button>
                        {another}
                      </>
                    );
                  }
                  if (query && search.failed) return <>{t('The place search isn’t answering. Just type the name.')}{another}</>;
                  if (query && search.found?.length === 0) return <>{t('Not found? Just type its name.')}{another}</>;
                  if (query && search.found?.length) {
                    return (
                      <>
                        {t('Tap the place, or keep typing your own name for it.')}{' '}
                        {search.found.some((p) => p.source === 'google') ? (
                          <span>{t('Places from Google Maps')}</span>
                        ) : (
                          <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">
                            {t('Places from OpenStreetMap')}
                          </a>
                        )}
                      </>
                    );
                  }
                  return another ?? null;
                })()}
              </span>
            )}
          </div>
          <div className="field">
            <span id="place-category">{t('Category')}</span>
            <div className="cat-picker" role="radiogroup" aria-labelledby="place-category">
              {categories.map((c) => (
                <button
                  key={c}
                  type="button"
                  role="radio"
                  aria-checked={fields.category === c}
                  className={fields.category === c ? 'on' : ''}
                  onClick={() => {
                    set('category', c);
                    setCategoryPicked(true);
                  }}
                >
                  <Icon name={c} size={18} />
                  {categoryName(c)}
                </button>
              ))}
            </div>
            <span className="hint">
              {categoryHint(categoryOf(fields.category))}. {t('Two fit? Pick what you’d go there for.')}
            </span>
          </div>
          <div className="two">
            <label className="field">
              {t('Area')}
              <input maxLength={100} placeholder={t('e.g. Station East')} value={fields.area} onChange={(e) => set('area', e.target.value)} />
            </label>
            <label className="field">
              {t('Kind')}
              <input
                maxLength={60}
                placeholder={kindPlaceholder(categoryOf(fields.category))}
                value={fields.cuisine}
                onChange={(e) => set('cuisine', e.target.value)}
              />
            </label>
          </div>
          {!id && been && (
            <>
              <label className="field">
                {t('When')}
                <input type="date" required max={today()} value={visitedOn} onChange={(e) => setVisitedOn(e.target.value)} />
              </label>
              <div className="field">
                <span>{t('Your rating')}</span>
                <StarPicker value={stars} onChange={setStars} />
              </div>
            </>
          )}
          {mapField}
          <label className="field">
            {t('Notes')}
            <textarea
              rows={3}
              maxLength={5000}
              placeholder={t('What to order, when to go, what to bring…')}
              value={fields.notes}
              onChange={(e) => set('notes', e.target.value)}
            />
          </label>
          <ErrorText message={error} />
          <div className="row end">
            <button type="button" className="secondary" onClick={() => navigate(id ? 'restaurant' : want ? 'want' : 'home', id ? { id } : undefined)}>
              {t('Cancel')}
            </button>
            <button type="submit" disabled={busy || !fields.name.trim()}>
              {id ? t('Save') : t('Add')}
            </button>
          </div>
        </form>
      )}
      {!fields && <ErrorText message={error} />}
    </section>
  );
}
