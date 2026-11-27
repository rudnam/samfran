// One place: both ratings, notes, whether you've been, and, once you have,
// each visit (with its photos) and what each of you said about it.
import { BackLink } from '../../components/BackLink';
import { useState, type FormEvent } from 'react';
import { api, errorMessage, type Restaurant } from '../../api';
import { useCached } from '../../cache';
import { confirmDialog, Skeleton } from '../../components/Overlay';
import { StarPicker } from '../../components/Stars';
import { t, tn } from '../../i18n';
import { href, replaceRoute } from '../../router';
import { Avatar, personTone, useSpace } from '../../space';
import { categoryName, categoryOf, CategoryTile, Icon } from './categories';
import { Comments } from './Comments';
import { Visits } from './Visits';
import { mapLink, shortDate, today } from './format';
import { ErrorText } from '../../components/ErrorText';

export function RestaurantDetail({ id }: { id: number }) {
  const { data: r, error: loadError, set: setR } = useCached(`restaurant:${id}`, () => api.restaurant(id), errorMessage);
  const [changeError, setError] = useState('');
  const error = changeError || loadError;
  const [busy, setBusy] = useState(false);
  // The date being picked for Mark as been, or null when closed.
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

  async function remove() {
    if (!r) return;
    const yes = await confirmDialog({
      title: t('Delete {name}?', { name: r.name }),
      body: t('Its ratings go with it. This can’t be undone.'),
      confirm: t('Delete'),
      danger: true,
    });
    if (!yes) return;
    setBusy(true);
    try {
      await api.deleteRestaurant(id);
      replaceRoute(r.visit_count > 0 ? 'home' : 'want');
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  if (!r) {
    return (
      <section className="stack">
        <BackLink href={href('home')} label="Our places" />
        {error ? (
          <ErrorText message={error} />
        ) : (
          <Skeleton rows={5} />
        )}
      </section>
    );
  }

  const mine = r.ratings.find((x) => x.me)?.stars ?? 0;
  const partner = space?.members.find((m) => !m.me);
  const theirs = r.ratings.find((x) => !x.me);
  const beenOn = r.last_visit;
  // The cover, big across the top: the original as it was taken, over its
  // thumbnail until it's in (an older server's place without cover_photo_id
  // shows just the thumbnail).
  const coverPhoto = (r.visits ?? []).flatMap((v) => v.photos ?? []).find((p) => p.id === r.cover_photo_id);
  return (
    <section className="stack">
      <BackLink href={href(beenOn ? 'home' : 'want')} label="Our places" />
      {r.cover && (
        <img
          className="place-cover"
          src={coverPhoto?.url ?? r.cover}
          style={{ backgroundImage: `url("${r.cover}")` }}
          alt={t('Cover photo')}
          decoding="async"
        />
      )}
      <header className="stack tight">
        <h1 className="display">{r.name}</h1>
        <p className="lead place-kind">
          <CategoryTile category={categoryOf(r.category)} size="sm" />
          {[categoryName(categoryOf(r.category)), r.cuisine, r.area].filter(Boolean).join(' · ')}
        </p>
        {beenOn && (
          <p className="been-line">
            {r.visit_count > 1
              ? tn(r.visit_count, 'Been · {date}', 'Been {n} times · last {date}', { date: shortDate(beenOn) })
              : t('Been · {date}', { date: shortDate(beenOn) })}
          </p>
        )}
        <div className="row wrap">
          {!beenOn && picking === null && (
            <button type="button" disabled={busy} onClick={() => setPicking(today())}>
              {t('Mark as been')}
            </button>
          )}
          <a className="button secondary" href={mapLink(r)} target="_blank" rel="noreferrer">
            <Icon name="map" size={20} />
            {t('Open in Maps')}
          </a>
          <a className="button secondary" href={href('edit', { id: r.id })}>
            <Icon name="edit" size={20} />
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
          </div>
        </form>
      )}

      <ErrorText message={error} />

      {beenOn && <Visits place={r} onChange={setR} />}

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
