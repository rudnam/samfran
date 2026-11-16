// The bus-stop alarm: choose the stop once, then "I'm on the bus" starts a
// trip on the phone (android/…/BusTripService.java), which rings before the
// stop. The phone does the watching; this page starts, shows and stops it.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, errorMessage, type BusStop, type Downloads, type FoundStop } from '../../api';
import { t } from '../../i18n';
import { busAlarm, isApp, type TripStatus } from '../../native';

const distances = [400, 600, 800, 1000, 1500, 2000];

function km(m: number): string {
  return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`;
}

export function BusPage() {
  const [stop, setStop] = useState<BusStop | null>(null);
  const [trip, setTrip] = useState<TripStatus | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);
  const [apk, setApk] = useState<Downloads['android']>(null);

  useEffect(() => {
    api.busStop().then(
      (s) => {
        setStop(s);
        setEditing(!s.set);
      },
      (err) => setError(errorMessage(err)),
    );
    if (!isApp) api.downloads().then((d) => setApk(d.android), () => {});
  }, []);

  // While a trip runs, show how far there is to go.
  useEffect(() => {
    if (!isApp) return;
    let alive = true;
    const poll = () => busAlarm.status().then((s) => alive && setTrip(s), () => {});
    poll();
    const id = setInterval(poll, 4000);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, []);

  async function run(fn: () => Promise<TripStatus>) {
    setBusy(true);
    setError('');
    try {
      setTrip(await fn());
    } catch (err) {
      const code = (err as { code?: string }).code;
      setError(code === 'LOCATION_DENIED' ? t('samfran needs your location to know when you’re near the stop. Allow it in the phone’s settings.') : errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const ready = stop?.set;
  return (
    <section className="stack">
      <header className="page-head">
        <div>
          <h1 className="display">{t('Bus')}</h1>
          <p className="subtitle">{t('Buzzes your phone before your stop, so you don’t miss it.')}</p>
        </div>
      </header>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {isApp && trip?.buzzing && (
        <div className="card stack trip on" role="status">
          <p className="trip-big">{t('Buzzing')}</p>
          <button type="button" disabled={busy} onClick={() => run(() => busAlarm.stopBuzz())}>
            {t('I’m up')}
          </button>
        </div>
      )}

      {isApp && ready && !trip?.buzzing && (
        <div className={trip?.running ? 'card stack trip on' : 'card stack trip'}>
          {trip?.running ? (
            <>
              <span className="eyebrow">{t('On the bus')}</span>
              <p className="trip-big">
                {trip.distance >= 0 ? t('{distance} to {stop}', { distance: km(trip.distance), stop: stop.name || t('your stop') }) : t('Finding where you are…')}
              </p>
              <p className="muted small">{t('It buzzes from {distance} before the stop until you tap I’m up. You can lock the phone.', { distance: km(stop.alert_meters) })}</p>
              <button type="button" className="secondary" disabled={busy} onClick={() => run(() => busAlarm.stop())}>
                {t('Stop')}
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className="big"
                disabled={busy}
                onClick={() => run(() => busAlarm.start({ lat: stop.lat, lng: stop.lng, radius: stop.alert_meters, name: stop.name }))}
              >
                {t('I’m on the bus')}
              </button>
              <p className="muted small">{t('Tap when you get on. It buzzes {distance} before {stop} and stops by itself after three hours.', { distance: km(stop.alert_meters), stop: stop.name || t('your stop') })}</p>
            </>
          )}
        </div>
      )}

      {!isApp && (
        <div className="card stack">
          <h2>{t('On the phone')}</h2>
          <p className="muted">{t('It runs in the samfran Android app: it uses the phone’s location during the ride, and nothing else. You can choose the stop here or in the app.')}</p>
          {apk && (
            <a className="button" href={apk.url} download>
              {t('Get the Android app')}
            </a>
          )}
        </div>
      )}

      {stop && (editing ? <StopForm stop={stop} onSaved={(s) => (setStop(s), setEditing(false))} onCancel={stop.set ? () => setEditing(false) : undefined} /> : (
        <div className="card stack tight">
          <div className="row spread">
            <div>
              <span className="eyebrow">{t('Your stop')}</span>
              <strong className="block">{stop.name || t('Unnamed stop')}</strong>
              <a className="small" href={`https://www.google.com/maps/search/?api=1&query=${stop.lat},${stop.lng}`} target="_blank" rel="noreferrer">
                {t('See it on the map')}
              </a>
            </div>
            <button type="button" className="secondary" onClick={() => setEditing(true)}>
              {t('Change')}
            </button>
          </div>
          <p className="muted small">{t('Buzzes {distance} before the stop.', { distance: km(stop.alert_meters) })}</p>
          {isApp && (
            <button type="button" className="link quiet" disabled={busy} onClick={() => run(() => busAlarm.test({ name: stop.name }))}>
              {t('Test the buzz')}
            </button>
          )}
        </div>
      ))}
    </section>
  );
}

/**
 * Stops matching what's typed in the name field, as it's typed: a quarter
 * second after the last key, two characters or more. null until searched.
 */
function useStopSearch(query: string, near: { lat: number; lng: number } | null) {
  const [found, setFound] = useState<FoundStop[] | null>(null);
  const [failed, setFailed] = useState(false);
  const latest = useRef(0);
  useEffect(() => {
    const q = query.trim();
    if ([...q].length < 2) {
      setFound(null);
      return;
    }
    const n = ++latest.current;
    const timer = setTimeout(() => {
      api.searchStops(q, near ?? undefined).then(
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

/** Choosing the stop: search by name, a Google Maps link to it, or where the phone is now. */
function StopForm({ stop, onSaved, onCancel }: { stop: BusStop; onSaved: (s: BusStop) => void; onCancel?: () => void }) {
  const [name, setName] = useState(stop.name);
  const [pos, setPos] = useState<{ lat: number; lng: number } | null>(stop.set ? { lat: stop.lat, lng: stop.lng } : null);
  const [link, setLink] = useState('');
  const [byName, setByName] = useState(false);
  // What's being searched for: the name as typed, until a stop is picked.
  const [query, setQuery] = useState('');
  const search = useStopSearch(query, pos);

  function pick(f: FoundStop) {
    setName(f.name);
    setPos({ lat: f.lat, lng: f.lng });
    setByName(false);
    setQuery('');
    setError('');
  }
  const [meters, setMeters] = useState(stop.alert_meters || 800);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function fromLink() {
    if (!link.trim()) return;
    setBusy(true);
    setError('');
    try {
      const p = await api.lookupMap(link.trim(), true);
      if (p.lat === undefined || p.lng === undefined) {
        setError(
          t('Couldn’t find where that stop is. In Google Maps, press and hold on the stop to drop a pin, then share that pin’s link.'),
        );
      } else {
        setPos({ lat: p.lat, lng: p.lng });
        setByName(!!p.found_by_name);
        // Google Maps calls a stop "Name（バス）".
        if (!name.trim() && p.name) setName(p.name.replace(/\s*[（(](バス|Bus)[）)]$/, ''));
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function fromHere() {
    setBusy(true);
    setError('');
    try {
      const h = await busAlarm.here();
      setPos({ lat: h.lat, lng: h.lng });
      setByName(false);
    } catch {
      setError(t('Couldn’t tell where you are. Check that location is on, and allowed for samfran.'));
    } finally {
      setBusy(false);
    }
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    // Enter while choosing from the list takes the first match.
    if (query && search.found?.length) {
      pick(search.found[0]);
      return;
    }
    if (!pos) return;
    setBusy(true);
    setError('');
    try {
      onSaved(await api.saveBusStop({ name, lat: pos.lat, lng: pos.lng, alert_meters: meters }));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="card stack" onSubmit={save}>
      <h2>{t('Where do you get off?')}</h2>
      <div className="field">
        <label htmlFor="stop-name">{t('Stop name')}</label>
        <input
          id="stop-name"
          maxLength={80}
          autoComplete="off"
          placeholder={t('Type part of it, e.g. 葵西')}
          value={name}
          aria-describedby="stop-search-hint"
          onChange={(e) => {
            setName(e.target.value);
            setQuery(e.target.value);
          }}
        />
        {query && search.found && search.found.length > 0 && (
          <ul className="suggest" aria-label={t('Matching stops')}>
            {search.found.map((f) => (
              <li key={`${f.name}${f.lat}${f.lng}`}>
                <button type="button" onClick={() => pick(f)}>
                  <span className="suggest-icon" aria-hidden="true">
                    {f.kind === 'train' ? '🚉' : '🚏'}
                  </span>
                  <span className="suggest-text">
                    <strong>{f.name}</strong>
                    {f.area && <span className="hint">{f.area}</span>}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        <span className="hint" id="stop-search-hint">
          {query && search.failed
            ? t('The stop search isn’t answering. Paste a Google Maps link instead.')
            : query && search.found?.length === 0
              ? t('No stops by that name. Try fewer characters, written as on the stop’s sign.')
              : query && search.found?.length
                ? (
                    <>
                      {t('Tap your stop.')}{' '}
                      <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">
                        {t('Stops from OpenStreetMap')}
                      </a>
                    </>
                  )
                : !pos && t('Start typing and choose your stop from the list.')}
        </span>
      </div>
      <div className="field">
        <span>{t('Or use a Google Maps link')}</span>
        <span className="row">
          <input
            type="url"
            aria-label={t('Google Maps link to the stop')}
            placeholder={t('Paste a Google Maps link to the stop')}
            value={link}
            onChange={(e) => setLink(e.target.value)}
            onBlur={fromLink}
          />
          <button type="button" className="secondary" disabled={busy || !link.trim()} onClick={fromLink}>
            {t('Use link')}
          </button>
        </span>
        {isApp && (
          <button type="button" className="link" disabled={busy} onClick={fromHere}>
            {t('Or use where I am now (stand at the stop)')}
          </button>
        )}
        {pos && (
          <span className="hint">
            ✓ {byName ? t('Found it on OpenStreetMap, by its name.') : t('Found it.')}{' '}
            <a href={`https://www.google.com/maps/search/?api=1&query=${pos.lat},${pos.lng}`} target="_blank" rel="noreferrer">
              {t('Check on the map')}
            </a>
          </span>
        )}
      </div>
      <label className="field">
        {t('Buzz this far before the stop')}
        <select value={meters} onChange={(e) => setMeters(Number(e.target.value))}>
          {distances.map((d) => (
            <option key={d} value={d}>
              {km(d)}
            </option>
          ))}
        </select>
        <span className="hint">{t('About one stop early is 600–800 m. On a fast road, choose more.')}</span>
      </label>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="row end">
        {onCancel && (
          <button type="button" className="secondary" onClick={onCancel}>
            {t('Cancel')}
          </button>
        )}
        <button type="submit" disabled={busy || !pos}>
          {t('Save')}
        </button>
      </div>
    </form>
  );
}
