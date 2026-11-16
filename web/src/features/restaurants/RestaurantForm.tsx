// Adding a place (been there, or want to go) and editing one.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, errorMessage, type FoundPlace, type Restaurant, type RestaurantFields } from '../../api';
import { StarPicker } from '../../components/Stars';
import { t } from '../../i18n';
import { href, navigate, replaceRoute } from '../../router';
import { isApp } from '../../native';
import { isMapsLink, parseShared } from '../../share';
import { today } from './format';

/**
 * Places matching what's typed in the name field, as it's typed: a quarter
 * second after the last key, two characters or more. null until searched.
 */
function usePlaceSearch(query: string, near: { lat: number; lng: number } | null) {
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
      api.searchPlaces(q, near ?? undefined).then(
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

/** An emoji for OpenStreetMap's kind of place. */
function kindIcon(kind: string): string {
  if (kind === 'cafe') return '☕';
  if (kind === 'bar' || kind === 'pub' || kind === 'biergarten') return '🍺';
  if (kind === 'fast_food') return '🍔';
  if (kind === 'ice_cream') return '🍦';
  if (kind === 'bakery' || kind === 'pastry' || kind === 'confectionery') return '🥐';
  return '🍽️';
}

/** Google Maps searching for the name around where it is: opens on that branch, not the chain. */
function pickedMapURL(p: FoundPlace): string {
  return `https://www.google.com/maps/search/${encodeURIComponent(p.name)}/@${p.lat},${p.lng},17z`;
}

const sameName = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();

const empty: RestaurantFields = { name: '', area: '', cuisine: '', map_url: '', notes: '' };

/**
 * id edits that restaurant; without it, adds one. want starts on the
 * want-to-go side. shared is text shared from Google Maps, to fill it in.
 */
export function RestaurantForm({ id, want, shared }: { id?: number; want?: boolean; shared?: string }) {
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
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // Searching by name, when adding: what's typed, until a place is picked.
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<FoundPlace | null>(null);
  // Near the bus stop first, if there is one: likely the right town.
  const [near, setNear] = useState<{ lat: number; lng: number } | null>(null);
  // What's already saved, to say so instead of adding it twice.
  const [saved, setSaved] = useState<Restaurant[]>([]);
  const search = usePlaceSearch(query, near);

  useEffect(() => {
    if (id) return;
    api.busStop().then(
      (s) => s.set && setNear({ lat: s.lat, lng: s.lng }),
      () => {},
    );
    api.restaurants().then(setSaved, () => {});
  }, [id]);

  const savedNamed = (name: string) => (name.trim() ? saved.find((r) => sameName(r.name, name)) : undefined);

  function pick(p: FoundPlace) {
    setFields((f) => (f ? { ...f, name: p.name, area: f.area.trim() ? f.area : p.area } : f));
    setPicked(p);
    setQuery('');
  }

  useEffect(() => {
    if (!id) return;
    api.restaurant(id).then(
      (r) => setFields({ name: r.name, area: r.area, cuisine: r.cuisine, map_url: r.map_url, notes: r.notes }),
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
      <a className="back" href={back}>
        ‹ {t('Back')}
      </a>
      <h1 className="display">{id ? t('Edit place') : t('Add a place')}</h1>
      {!fields && !error && <p className="muted">{t('Loading…')}</p>}
      {!id && fields && !shared && isApp && <p className="hint">{t('Share a place from Google Maps to SamFran to add it in one tap.')}</p>}
      {fields && (
        <form className="card stack" onSubmit={submit}>
          {!id && (
            <div className="segmented" role="radiogroup" aria-label={t('Have you been?')}>
              <button type="button" role="radio" aria-checked={been} className={been ? 'on' : ''} onClick={() => setBeen(true)}>
                {t('We’ve been')}
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
              placeholder={lookingUp ? t('Looking up the place…') : id ? undefined : t('Type to search, e.g. すき家')}
              onChange={(e) => {
                set('name', e.target.value);
                if (!id) setQuery(e.target.value);
              }}
              autoFocus={!id && !shared}
            />
            {query && search.found && search.found.length > 0 && (
              <ul className="suggest" aria-label={t('Matching places')}>
                {search.found.map((p) => {
                  const already = savedNamed(p.name);
                  return (
                    <li key={`${p.name}${p.lat}${p.lng}`}>
                      {already ? (
                        <a className="suggest-row" href={href('restaurant', { id: already.id })}>
                          <span className="suggest-icon" aria-hidden="true">
                            {kindIcon(p.kind)}
                          </span>
                          <span className="suggest-text">
                            <strong>{p.name}</strong>
                            <span className="hint">{t('Already on your list')}</span>
                          </span>
                        </a>
                      ) : (
                        <button type="button" className="suggest-row" onClick={() => pick(p)}>
                          <span className="suggest-icon" aria-hidden="true">
                            {kindIcon(p.kind)}
                          </span>
                          <span className="suggest-text">
                            <strong>{p.name}</strong>
                            {(p.street || p.area) && <span className="hint">{[p.street, p.area].filter(Boolean).join(' · ')}</span>}
                          </span>
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
                  const dup = savedNamed(fields.name);
                  if (dup) {
                    return (
                      <>
                        {t('Already on your list:')} <a href={href('restaurant', { id: dup.id })}>{dup.name}</a>
                      </>
                    );
                  }
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
                      </>
                    );
                  }
                  if (query && search.failed) return t('The place search isn’t answering. Just type the name.');
                  if (query && search.found?.length === 0) return t('Not found? Just type its name.');
                  if (query && search.found?.length) {
                    return (
                      <>
                        {t('Tap the place, or keep typing your own name for it.')}{' '}
                        <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">
                          {t('Places from OpenStreetMap')}
                        </a>
                      </>
                    );
                  }
                  return !fields.name && t('Start typing and pick it from the list, or just type its name.');
                })()}
              </span>
            )}
          </div>
          <div className="two">
            <label className="field">
              {t('Area')}
              <input maxLength={100} placeholder={t('e.g. Station East')} value={fields.area} onChange={(e) => set('area', e.target.value)} />
            </label>
            <label className="field">
              {t('Food')}
              <input maxLength={60} placeholder={t('e.g. Ramen')} value={fields.cuisine} onChange={(e) => set('cuisine', e.target.value)} />
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
              placeholder={t('What to order, when to book…')}
              value={fields.notes}
              onChange={(e) => set('notes', e.target.value)}
            />
          </label>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
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
      {!fields && error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </section>
  );
}
