// Adding a place (been there, or want to go) and editing one.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type RestaurantFields } from '../../api';
import { StarPicker } from '../../components/Stars';
import { t } from '../../i18n';
import { href, navigate, replaceRoute } from '../../router';
import { isApp } from '../../native';
import { isMapsLink, parseShared } from '../../share';
import { today } from './format';

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
  const [been, setBeen] = useState(!want);
  const [visitedOn, setVisitedOn] = useState(today());
  const [stars, setStars] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

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
        : await api.createRestaurant({ ...fields, ...(been ? { visit: { visited_on: visitedOn }, stars: stars || undefined } : {}) });
      // Back goes to the list, not to this form.
      replaceRoute('restaurant', { id: saved.id });
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  const back = id ? href('restaurant', { id }) : href(want ? 'want' : 'home');
  return (
    <section className="stack">
      <a className="back" href={back}>
        ‹ {t('Back')}
      </a>
      <h1 className="display">{id ? t('Edit place') : t('Add a place')}</h1>
      {!fields && !error && <p className="muted">{t('Loading…')}</p>}
      {!id && fields && !shared && (
        <p className="hint">
          {isApp ? t('Share a place from Google Maps to samfran to add it in one tap.') : t('Paste a Google Maps link to fill in the name.')}
        </p>
      )}
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
          <label className="field">
            {t('Name')}
            <input
              required
              maxLength={200}
              value={fields.name}
              placeholder={lookingUp ? t('Looking up the place…') : undefined}
              onChange={(e) => set('name', e.target.value)}
              autoFocus={!id && !shared}
            />
          </label>
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
          <label className="field">
            {t('Map link')}
            <input
              type="url"
              inputMode="url"
              placeholder="https://maps.app.goo.gl/…"
              value={fields.map_url}
              onChange={(e) => set('map_url', e.target.value)}
              onPaste={(e) => {
                if (pasteLink(e.clipboardData.getData('text'))) e.preventDefault();
              }}
              onBlur={(e) => void nameFromLink(e.target.value.trim())}
            />
            <span className="hint">{t('Optional. Without one, “Open in Maps” searches for the name.')}</span>
          </label>
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
