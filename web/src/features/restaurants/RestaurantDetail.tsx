// One place: both ratings, notes, every visit, and logging another.
import { BackLink } from '../../components/BackLink';
import { useState, type FormEvent } from 'react';
import { api, errorMessage, type Restaurant } from '../../api';
import { useCached } from '../../cache';
import { confirmDialog, dropToast, showToast, Skeleton } from '../../components/Overlay';
import { StarPicker } from '../../components/Stars';
import { t, tn } from '../../i18n';
import { href, replaceRoute } from '../../router';
import { Avatar, personTone, useSpace } from '../../space';
import { mapLink, shortDate, today } from './format';

export function RestaurantDetail({ id }: { id: number }) {
  const { data: r, error: loadError, set: setR } = useCached(`restaurant:${id}`, () => api.restaurant(id), errorMessage);
  const [changeError, setError] = useState('');
  const error = changeError || loadError;
  const [busy, setBusy] = useState(false);
  // A visit just removed: hidden, and deleted for real once Undo's time is up.
  const [removing, setRemoving] = useState(0);
  const [visitOn, setVisitOn] = useState(today());
  const [visitNote, setVisitNote] = useState('');
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

  async function logVisit(e: FormEvent) {
    e.preventDefault();
    if (await change(() => api.addVisit(id, visitOn, visitNote))) {
      setVisitNote('');
      setVisitOn(today());
    }
  }

  /** Hides the visit and offers Undo; without it, the visit is deleted when the offer ends. */
  function removeVisit(visit: number) {
    showToast({
      message: t('Visit removed.'),
      action: t('Undo'),
      onAction: () => setRemoving(0),
      onClose: () => {
        api.deleteVisit(id, visit).then(
          (fresh) => {
            setR(fresh);
            setRemoving(0);
          },
          (err) => {
            setError(errorMessage(err));
            setRemoving(0);
          },
        );
      },
    });
    setRemoving(visit); // after showToast, which finishes an earlier removal first
  }

  async function remove() {
    if (!r) return;
    const yes = await confirmDialog({
      title: t('Delete {name}?', { name: r.name }),
      body: t('Its visits and ratings go with it. This can’t be undone.'),
      confirm: t('Delete'),
      danger: true,
    });
    if (!yes) return;
    dropToast(); // a visit waiting to be removed goes with the place
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
  const visits = (r.visits ?? []).filter((v) => v.id !== removing);
  const visitCount = r.visit_count - (removing ? 1 : 0);
  return (
    <section className="stack">
      <BackLink href={href(r.visit_count > 0 ? 'home' : 'want')} label="Our spots" />
      <header className="stack tight">
        <h1 className="display">{r.name}</h1>
        {(r.area || r.cuisine) && <p className="lead">{[r.area, r.cuisine].filter(Boolean).join(' · ')}</p>}
        <div className="row wrap">
          <a className="button secondary" href={mapLink(r)} target="_blank" rel="noreferrer">
            {t('Open in Maps')}
          </a>
          <a className="button secondary" href={href('edit', { id: r.id })}>
            {t('Edit')}
          </a>
        </div>
      </header>

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

      <div className="card stack">
        <div className="row spread">
          <h2>{t('Visits')}</h2>
          <span className="muted">{visitCount > 0 ? tn(visitCount, '{n} visit', '{n} visits') : t('Not yet')}</span>
        </div>
        <form className="visit-form" onSubmit={logVisit}>
          <input type="date" aria-label={t('Date')} required max={today()} value={visitOn} onChange={(e) => setVisitOn(e.target.value)} />
          <input
            aria-label={t('Note about this visit')}
            placeholder={t('What did you have? (optional)')}
            maxLength={1000}
            value={visitNote}
            onChange={(e) => setVisitNote(e.target.value)}
          />
          <button type="submit" disabled={busy}>
            {visitCount > 0 ? t('Log visit') : t('We went!')}
          </button>
        </form>
        {visits.length > 0 && (
          <ul className="visits" aria-label={t('Visits')}>
            {visits.map((v) => (
              <li key={v.id}>
                <span className="visit-date">{shortDate(v.visited_on)}</span>
                <span className="grow">
                  {v.note || <span className="muted">{v.added_by ? t('Logged by {name}', { name: v.added_by }) : ''}</span>}
                </span>
                <button
                  type="button"
                  className="link quiet"
                  disabled={busy}
                  aria-label={t('Delete the visit on {date}', { date: shortDate(v.visited_on) })}
                  onClick={() => removeVisit(v.id)}
                >
                  {t('Remove')}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <button type="button" className="link danger" disabled={busy} onClick={remove}>
        {t('Delete this place')}
      </button>
    </section>
  );
}
