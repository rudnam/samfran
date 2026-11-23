// One place: both ratings, notes, whether you've been (and when), and, once
// you have, what each of you said about it.
import { BackLink } from '../../components/BackLink';
import { useState, type FormEvent } from 'react';
import { api, errorMessage, type Restaurant } from '../../api';
import { useCached } from '../../cache';
import { confirmDialog, dropToast, showToast, Skeleton } from '../../components/Overlay';
import { StarPicker } from '../../components/Stars';
import { t } from '../../i18n';
import { href, replaceRoute } from '../../router';
import { Avatar, personTone, useSpace } from '../../space';
import { categoryName, categoryOf, CategoryTile } from './categories';
import { Comments } from './Comments';
import { mapLink, shortDate, today } from './format';

export function RestaurantDetail({ id }: { id: number }) {
  const { data: r, error: loadError, set: setR } = useCached(`restaurant:${id}`, () => api.restaurant(id), errorMessage);
  const [changeError, setError] = useState('');
  const error = changeError || loadError;
  const [busy, setBusy] = useState(false);
  // "Not been yet" just chosen: shown so, and saved once Undo's time is up.
  const [clearing, setClearing] = useState(false);
  // The date being picked (Mark as been, or Change), or null when closed.
  const [picking, setPicking] = useState<string | null>(null);
  const { space } = useSpace();

  /** Runs a change that answers with the restaurant as it now is. */
  async function change(fn: () => Promise<Restaurant>) {
    setBusy(true);
    setError('');
    try {
      setR(await fn());
      return true;
    } catch (err) {
      setError(errorMessage(err));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function saveBeen(e: FormEvent) {
    e.preventDefault();
    if (picking && (await change(() => api.setBeen(id, picking)))) setPicking(null);
  }

  /** Back to want to go, with Undo; without it, it's saved when the offer ends. */
  function notBeen() {
    setPicking(null);
    showToast({
      message: t('Moved to Want to go.'),
      action: t('Undo'),
      onAction: () => setClearing(false),
      onClose: () => {
        api.clearBeen(id).then(
          (fresh) => {
            setR(fresh);
            setClearing(false);
          },
          (err) => {
            setError(errorMessage(err));
            setClearing(false);
          },
        );
      },
    });
    setClearing(true); // after showToast, which finishes an earlier one first
  }

  async function remove() {
    if (!r) return;
    const yes = await confirmDialog({
      title: t('Delete {name}?', { name: r.name }),
      body: t('Its ratings go with it. This can’t be undone.'),
      confirm: t('Delete'),
      danger: true,
    });
    if (!yes) return;
    dropToast(); // a 'not been yet' waiting to be saved goes with the place
    setBusy(true);
    try {
      await api.deleteRestaurant(id);
      replaceRoute(r.visit_count > 0 && !clearing ? 'home' : 'want');
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  if (!r) {
    return (
      <section className="stack">
        <BackLink href={href('home')} label="Our spots" />
        {error ? (
          <p role="alert" className="error">
            {error}
          </p>
        ) : (
          <Skeleton rows={5} />
        )}
      </section>
    );
  }

  const mine = r.ratings.find((x) => x.me)?.stars ?? 0;
  const partner = space?.members.find((m) => !m.me);
  const theirs = r.ratings.find((x) => !x.me);
  const beenOn = clearing ? null : r.last_visit;
  return (
    <section className="stack">
      <BackLink href={href(beenOn ? 'home' : 'want')} label="Our spots" />
      <header className="stack tight">
        <h1 className="display">{r.name}</h1>
        <p className="lead place-kind">
          <CategoryTile category={categoryOf(r.category)} size="sm" />
          {[categoryName(categoryOf(r.category)), r.cuisine, r.area].filter(Boolean).join(' · ')}
        </p>
        {beenOn && (
          <p className="been-line">
            {t('Been · {date}', { date: shortDate(beenOn) })}
            {picking === null && (
              <>
                {' · '}
                <button type="button" className="link" disabled={busy} onClick={() => setPicking(beenOn)}>
                  {t('Change')}
                </button>
              </>
            )}
          </p>
        )}
        <div className="row wrap">
          {!beenOn && picking === null && (
            <button type="button" disabled={busy} onClick={() => setPicking(today())}>
              {t('Mark as been')}
            </button>
          )}
          <a className="button secondary" href={mapLink(r)} target="_blank" rel="noreferrer">
            {t('Open in Maps')}
          </a>
          <a className="button secondary" href={href('edit', { id: r.id })}>
            {t('Edit')}
          </a>
        </div>
      </header>

      {picking !== null && (
        <form className="card been-form" onSubmit={saveBeen}>
          <label className="field">
            {t('When did you go?')}
            <input type="date" required max={today()} value={picking} onChange={(e) => setPicking(e.target.value)} />
          </label>
          <div className="row wrap">
            <button type="submit" disabled={busy}>
              {t('Save')}
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setPicking(null)}>
              {t('Cancel')}
            </button>
            {beenOn && (
              <button type="button" className="link quiet" disabled={busy} onClick={notBeen}>
                {t('Not been yet')}
              </button>
            )}
          </div>
        </form>
      )}

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <div className="card stack">
        <h2>{t('Ratings')}</h2>
        <div className="rating-row">
          <span className="who">{t('You')}</span>
          <StarPicker value={mine} disabled={busy} onChange={(n) => change(() => api.rate(id, n))} />
        </div>
        {(partner || theirs) && (
          <div className="rating-row">
            <span className="who">
              <Avatar name={(theirs ?? partner)!.name} tone={personTone(space, (theirs ?? partner)!.name)} />
              {(theirs ?? partner)!.name}
            </span>
            {theirs ? (
              <span className="stars-static" aria-label={t('{n} stars', { n: theirs.stars })}>
                {'★'.repeat(theirs.stars)}
                <span className="off">{'★'.repeat(5 - theirs.stars)}</span>
              </span>
            ) : (
              <span className="muted">{t('Not rated yet')}</span>
            )}
          </div>
        )}
      </div>

      {r.notes && (
        <div className="card stack tight">
          <h2>{t('Notes')}</h2>
          <p className="prewrap">{r.notes}</p>
        </div>
      )}

      {beenOn && <Comments place={r} onChange={setR} />}

      <button type="button" className="link danger" disabled={busy} onClick={remove}>
        {t('Delete this place')}
      </button>
    </section>
  );
}
