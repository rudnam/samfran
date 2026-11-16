// The bus-stop alarm. Choose the stop here; the alarm itself runs in the
// Android app, which buzzes before the stop until "Stop". This page explains
// it, sets it up, and offers the app.
//
// "Try it now" is a practice run: a pretend ride of a few seconds that ends
// the way a real one does. It vibrates where the browser can, so anyone can
// see what it does before relying on it.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, errorMessage, type BusStop, type Downloads, type FoundStop } from '../../api';
import { t, type Key } from '../../i18n';
import { StopMap } from './StopMap';

const distances = [400, 600, 800, 1000, 1500, 2000];

function km(m: number): string {
  return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`;
}

/** What a buzz distance means on a ride, roughly: city bus stops are 300–500 m apart. */
function early(m: number): Key {
  if (m <= 400) return 'Right before the stop.';
  if (m <= 800) return 'About one stop early.';
  if (m <= 1500) return 'About two stops early.';
  return 'Plenty of time to wake up.';
}

/** How long the pretend ride of a practice run takes, before it buzzes. */
const practiceMs = 6000;

type Practice = { phase: 'riding'; startedAt: number } | { phase: 'buzzing' } | { phase: 'done' } | null;

export function BusPage() {
  const [stop, setStop] = useState<BusStop | null>(null);
  const [error, setError] = useState('');
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
    api.downloads().then((d) => setApk(d.android), () => {});
  }, []);

  // ---- The practice run ----
  const [practice, setPractice] = useState<Practice>(null);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    if (practice?.phase !== 'riding') return;
    const id = setInterval(() => {
      setNow(Date.now());
      if (Date.now() - practice.startedAt >= practiceMs) buzzForPractice();
    }, 100);
    return () => clearInterval(id);
  }, [practice]); // eslint-disable-line react-hooks/exhaustive-deps

  function buzzForPractice() {
    setPractice({ phase: 'buzzing' });
    webBuzz(true);
  }

  function endPractice() {
    webBuzz(false);
    setPractice(null);
  }

  // Leaving the page stops a web practice buzz.
  useEffect(() => () => webBuzz(false), []);

  function imUp() {
    webBuzz(false);
    setPractice({ phase: 'done' });
  }

  const ready = !!stop?.set;
  const buzzing = practice?.phase === 'buzzing';

  return (
    <section className="stack">
      <header className="page-head">
        <div>
          <h1 className="display">{t('Bus alarm')}</h1>
          <p className="subtitle">{t('Buzzes your phone before your stop, so you don’t miss it.')}</p>
        </div>
      </header>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {stop && buzzing && (
        <div className="card stack buzz" role="alertdialog" aria-label={t('Your stop is next')}>
          <span className="eyebrow">{t('Practice run')}</span>
          <p className="buzz-big">{t('Your stop is next')}</p>
          <p className="buzz-stop">{stop.name || t('your stop')}</p>
          <button type="button" className="big wake" onClick={imUp}>
            {t('Stop')}
          </button>
          <p className="small buzz-note">{t('On the phone, it vibrates like this until you tap Stop, even with the phone locked.')}</p>
        </div>
      )}

      {stop && ready && !buzzing && !editing && (
        <RideCard
          stop={stop}
          practice={practice}
          practiceLeft={practice?.phase === 'riding' ? Math.max(0, 1 - (now - practice.startedAt) / practiceMs) : 0}
          apk={apk}
          onPractice={() => {
            setError('');
            setNow(Date.now());
            setPractice({ phase: 'riding', startedAt: Date.now() });
          }}
          onEndPractice={endPractice}
        />
      )}

      {stop && !ready && <HowItWorks />}

      {stop && editing && (
        <StopForm
          stop={stop}
          first={!ready}
          onSaved={(s) => {
            setStop(s);
            setEditing(false);
          }}
          onCancel={ready ? () => setEditing(false) : undefined}
        />
      )}

      {stop && ready && !editing && (
        <AlarmSettings
          stop={stop}
          onChange={() => setEditing(true)}
          onSaved={setStop}
        />
      )}

      {stop && ready && !editing && (
        <details className="card how-fold">
          <summary>{t('How it works')}</summary>
          <Steps />
        </details>
      )}
    </section>
  );
}

/** Vibrates on repeat where the browser can (Android's Chrome), until stopped. Nothing on the others. */
let vibrating = false;
function webBuzz(on: boolean) {
  if (typeof navigator === 'undefined' || !('vibrate' in navigator) || on === vibrating) return;
  vibrating = on;
  try {
    // About 30 seconds of the app's pattern: 0.8 s on, 0.6 s off.
    navigator.vibrate(on ? Array.from({ length: 42 }, (_, i) => (i % 2 ? 600 : 800)) : 0);
  } catch {
    // Not allowed here: the practice run still shows what happens.
  }
}

function Steps() {
  return (
    <ol className="how">
      <li>
        <span className="how-num" aria-hidden="true">1</span>
        <span>
          <strong className="block">{t('Choose your stop')}</strong>
          <span className="muted small">{t('Just once. It’s kept for every ride.')}</span>
        </span>
      </li>
      <li>
        <span className="how-num" aria-hidden="true">2</span>
        <span>
          <strong className="block">{t('Tap “I’m on the bus”')}</strong>
          <span className="muted small">{t('When you get on. Then lock the phone and rest.')}</span>
        </span>
      </li>
      <li>
        <span className="how-num" aria-hidden="true">3</span>
        <span>
          <strong className="block">{t('It buzzes before your stop')}</strong>
          <span className="muted small">{t('It keeps buzzing until you tap “Stop”.')}</span>
        </span>
      </li>
    </ol>
  );
}

function HowItWorks() {
  return (
    <div className="card stack tight">
      <h2>{t('How it works')}</h2>
      <Steps />
      <p className="muted small">{t('The alarm runs in the SamFran Android app. You can choose the stop here or there.')}</p>
    </div>
  );
}

/** The ride as a line: the bus moving along it towards the stop, with the stretch where it buzzes. */
function Route({ progress, arrived }: { progress: number; arrived?: boolean }) {
  const zone = 24; // % of the line, at the end, where it buzzes
  const at = arrived ? 100 - zone / 2 : Math.min(1, Math.max(0, progress)) * (100 - zone);
  return (
    <div className="route" aria-hidden="true">
      <span className="route-line" />
      <span className="route-done" style={{ width: `${at}%` }} />
      <span className="route-zone" style={{ width: `${zone}%` }} />
      <span className="route-bus" style={{ left: `${at}%` }}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
          <rect x="5" y="3" width="14" height="15" rx="3" />
          <path d="M5 11h14M8 21v-3M16 21v-3" />
        </svg>
      </span>
      <span className="route-stop" />
    </div>
  );
}

function RideCard(p: {
  stop: BusStop;
  practice: Practice;
  /** 1 to 0, through the pretend ride. */
  practiceLeft: number;
  apk: Downloads['android'];
  onPractice: () => void;
  onEndPractice: () => void;
}) {
  const { stop } = p;
  const name = stop.name || t('your stop');
  const alert = stop.alert_meters;

  if (p.practice?.phase === 'riding') {
    const start = alert + 1800;
    const d = alert + (start - alert) * p.practiceLeft;
    return (
      <div className="card stack trip on" role="status">
        <span className="eyebrow">{t('Practice run')}</span>
        <p className="trip-big">{t('{distance} to {stop}', { distance: km(d), stop: name })}</p>
        <Route progress={1 - p.practiceLeft} />
        <p className="small">{t('Pretend you’ve dozed off… it buzzes {distance} before the stop.', { distance: km(alert) })}</p>
        <button type="button" className="secondary" onClick={p.onEndPractice}>
          {t('End practice')}
        </button>
      </div>
    );
  }

  if (p.practice?.phase === 'done') {
    return (
      <div className="card stack trip done" role="status">
        <p className="trip-big">{t('✓ That’s it')}</p>
        <p className="small">
          {t('On a real ride, start it in the SamFran Android app when you get on.')}
        </p>
        <button type="button" className="secondary" onClick={p.onEndPractice}>
          {t('Done')}
        </button>
      </div>
    );
  }

  return (
    <div className="card stack trip">
      <div>
        <span className="eyebrow">{t('Your stop')}</span>
        <p className="trip-stop">{name}</p>
        <p className="muted small">
          {t('Buzzes {distance} before the stop.', { distance: km(alert) })} {t(early(alert))}
        </p>
      </div>
      <StopMap lat={stop.lat} lng={stop.lng} name={stop.name} />
      <button type="button" className="big" onClick={p.onPractice}>
        {t('Try it now')}
      </button>
      <p className="muted small center">{t('A six-second practice ride, to see what happens.')}</p>
      <div className="app-note">
        <p className="small">{t('The real alarm runs in the SamFran Android app. It uses the phone’s location during the ride, and nothing else.')}</p>
        {p.apk && (
          <a className="button secondary" href={p.apk.url} download>
            {t('Get the Android app')}
          </a>
        )}
      </div>
    </div>
  );
}

function DistancePicker({ value, onPick, disabled }: { value: number; onPick: (m: number) => void; disabled?: boolean }) {
  return (
    <div className="field">
      <span id="buzz-distance">{t('Buzz this far before the stop')}</span>
      <div className="chips" role="radiogroup" aria-labelledby="buzz-distance">
        {distances.map((d) => (
          <button key={d} type="button" role="radio" aria-checked={value === d} className={value === d ? 'on' : ''} disabled={disabled} onClick={() => onPick(d)}>
            {km(d)}
          </button>
        ))}
      </div>
      <span className="hint">{t(early(value))} {t('On a fast road, choose more.')}</span>
    </div>
  );
}

function AlarmSettings(p: {
  stop: BusStop;
  onChange: () => void;
  onSaved: (s: BusStop) => void;
}) {
  const { stop } = p;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function setDistance(m: number) {
    if (m === stop.alert_meters) return;
    setBusy(true);
    setError('');
    try {
      p.onSaved(await api.saveBusStop({ name: stop.name, lat: stop.lat, lng: stop.lng, alert_meters: m }));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card stack">
      <h2>{t('Alarm settings')}</h2>
      <div className="row spread">
        <div className="grow">
          <span className="eyebrow">{t('Your stop')}</span>
          <strong className="block">{stop.name || t('Unnamed stop')}</strong>
          <a className="small" href={`https://www.google.com/maps/search/?api=1&query=${stop.lat},${stop.lng}`} target="_blank" rel="noreferrer">
            {t('See it on the map')}
          </a>
        </div>
        <button type="button" className="secondary" onClick={p.onChange}>
          {t('Change')}
        </button>
      </div>
      <DistancePicker value={stop.alert_meters} onPick={(m) => void setDistance(m)} disabled={busy} />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </div>
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

/** Choosing the stop: search by name, or (folded away) a Google Maps link to it or where the phone is now. */
function StopForm({ stop, first, onSaved, onCancel }: { stop: BusStop; first: boolean; onSaved: (s: BusStop) => void; onCancel?: () => void }) {
  const [name, setName] = useState(stop.name);
  const [pos, setPos] = useState<{ lat: number; lng: number } | null>(stop.set ? { lat: stop.lat, lng: stop.lng } : null);
  const [link, setLink] = useState('');
  const [byName, setByName] = useState(false);
  // What's being searched for: the name as typed, until a stop is picked.
  const [query, setQuery] = useState('');
  const search = useStopSearch(query, pos);
  const [other, setOther] = useState(false);

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
      {first && <span className="eyebrow">{t('Step 1 of 2')}</span>}
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
        {pos && !query && <StopMap lat={pos.lat} lng={pos.lng} name={name} />}
        {pos && !query && (
          <span className="found">
            ✓ {byName ? t('Found it on OpenStreetMap, by its name.') : t('Found it.')}{' '}
            <a href={`https://www.google.com/maps/search/?api=1&query=${pos.lat},${pos.lng}`} target="_blank" rel="noreferrer">
              {t('Check on the map')}
            </a>
          </span>
        )}
      </div>

      <details className="other-ways" open={other} onToggle={(e) => setOther(e.currentTarget.open)}>
        <summary>{t('Can’t find it? Other ways')}</summary>
        <div className="stack tight">
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
        </div>
      </details>

      {first && <span className="eyebrow">{t('Step 2 of 2')}</span>}
      <DistancePicker value={meters} onPick={setMeters} />

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
