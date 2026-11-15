// One place: both ratings, notes, every visit, and logging another.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Restaurant } from '../../api';
import { StarPicker } from '../../components/Stars';
import { t, tn } from '../../i18n';
import { href, replaceRoute } from '../../router';
import { Avatar, personTone, useSpace } from '../../space';
import { mapLink, shortDate, today } from './format';

export function RestaurantDetail({ id }: { id: number }) {
  const [r, setR] = useState<Restaurant | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [visitOn, setVisitOn] = useState(today());
  const [visitNote, setVisitNote] = useState('');
  const { space } = useSpace();

  useEffect(() => {
    api.restaurant(id).then(setR, (err) => setError(errorMessage(err)));
  }, [id]);

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

  async function remove() {
    if (!r || !confirm(t('Delete {name} and all its visits? This can’t be undone.', { name: r.name }))) return;
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
        <a className="back" href={href('home')}>
          ‹ {t('Our spots')}
        </a>
        {error ? (
          <p role="alert" className="error">
            {error}
          </p>
        ) : (
          <p className="muted">{t('Loading…')}</p>
        )}
      </section>
    );
  }

  const mine = r.ratings.find((x) => x.me)?.stars ?? 0;
  const partner = space?.members.find((m) => !m.me);
  const theirs = r.ratings.find((x) => !x.me);
  return (
    <section className="stack">
      <a className="back" href={href(r.visit_count > 0 ? 'home' : 'want')}>
        ‹ {t('Our spots')}
      </a>
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
          <span className="muted">{r.visit_count > 0 ? tn(r.visit_count, '{n} visit', '{n} visits') : t('Not yet')}</span>
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
            {r.visit_count > 0 ? t('Log visit') : t('We went!')}
          </button>
        </form>
        {r.visits && r.visits.length > 0 && (
          <ul className="visits" aria-label={t('Visits')}>
            {r.visits.map((v) => (
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
                  onClick={() => change(() => api.deleteVisit(id, v.id))}
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
