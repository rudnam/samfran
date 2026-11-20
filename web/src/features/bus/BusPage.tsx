// The ride alarm, for the bus or the train. Choose the stop (a bus stop or a
// station) here; the alarm itself runs in the Android app, which buzzes
// before the stop until "Stop", adding a sound if it isn't stopped. This
// page explains it, sets it up, and offers the app.
//
// A person can keep several stops, as they get off at different ones after
// different shifts: a stop can be the one for some kinds of shift, and the
// page picks the stop for today's shift, or else the one used last. Picking
// another is for this visit only.
//
// "Try it now" is a practice run: a pretend ride of a few seconds that ends
// the way a real one does. It vibrates where the browser can, so anyone can
// see what it does before relying on it.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, errorMessage, type BusStops, type Buzz, type Downloads, type FoundStop, type SavedStop, type ShiftDay, type ShiftLabel, type StopKind } from '../../api';
import { t, type Key } from '../../i18n';
import { labelOf } from '../shifts/codes';
import { StopMap } from './StopMap';

const shiftKinds: ShiftLabel[] = ['Early', 'Middle', 'Late', 'Night'];

/**
 * The shift you'd be going home from: one that ended in the last six hours,
 * or else the next to end within a day. As the Android app works it out
 * (homeShift, in core/model's Bus.kt).
 */
export function homeShift(days: ShiftDay[], now: number): ShiftDay | null {
  const ends = days
    .filter((d) => d.kind === 'work' && d.end)
    .map((d) => ({ d, end: Date.parse(d.end!) }))
    .filter(({ end }) => end > now - 6 * 3600_000 && end < now + 24 * 3600_000)
    .sort((a, b) => a.end - b.end);
  return ends[0]?.d ?? null;
}

/** The stop for a shift, if one is for its kind. */
function stopFor(all: BusStops, shift: ShiftDay | null): SavedStop | undefined {
  return shift ? all.stops.find((s) => s.shifts.includes(shift.label as ShiftLabel)) : undefined;
}

/** How many stops a person can keep (MaxStops, in internal/bus). */
const maxStops = 10;

/**
 * The choices for how far before the stop to buzz, and the one to start
 * from, for each kind of stop (Distances, in core/model's Bus.kt): bus stops
 * are 300–500 m apart in a city; stations a few km, and trains go faster.
 */
const distances: Record<StopKind, number[]> = { bus: [400, 600, 800, 1000, 1500, 2000], train: [1000, 1500, 2000, 3000, 4000, 5000] };
const defaultMeters: Record<StopKind, number> = { bus: 800, train: 2000 };

/** How long it buzzes before adding the phone's alarm sound, if not stopped (0: never). */
const soundAfters: { s: number; label: Key }[] = [
  { s: 0, label: 'Never' },
  { s: 30, label: '30 s' },
  { s: 60, label: '1 min' },
  { s: 120, label: '2 min' },
];

/**
 * The ways it can buzz, as the Android app does them (Buzz, in core/model's Bus.kt):
 * milliseconds on, then off, repeating. The app also buzzes "strong" and
 * "strongest" at the phone's full strength; a browser can't choose the
 * strength, only how long.
 */
const buzzes: { buzz: Buzz; label: Key; about: Key; on: number; off: number }[] = [
  { buzz: 'gentle', label: 'Gentle', about: 'Short buzzes, at the phone’s usual strength.', on: 800, off: 600 },
  { buzz: 'strong', label: 'Strong', about: 'Longer buzzes, at full strength.', on: 1000, off: 300 },
  { buzz: 'strongest', label: 'Strongest', about: 'Full strength, with hardly a pause.', on: 2000, off: 100 },
];

function buzzOf(b: Buzz | undefined) {
  return buzzes.find((x) => x.buzz === b) ?? buzzes[1];
}

function km(m: number): string {
  return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`;
}

/** What a buzz distance means on a ride, roughly (Distances.early, in core/model's Bus.kt). */
function early(m: number, kind: StopKind): Key {
  if (kind === 'train') {
    if (m <= 1000) return 'About a minute early.';
    if (m <= 2000) return 'About one to two minutes early.';
    if (m <= 3000) return 'About two to three minutes early.';
    return 'Plenty of time to wake up.';
  }
  if (m <= 400) return 'Right before the stop.';
  if (m <= 800) return 'About one stop early.';
  if (m <= 1500) return 'About two stops early.';
  return 'Plenty of time to wake up.';
}

/** How long the pretend ride of a practice run takes, before it buzzes. */
const practiceMs = 6000;

type Practice = { phase: 'riding'; startedAt: number } | { phase: 'buzzing' } | { phase: 'done' } | null;

/** The stop being changed, or "new" for adding one. */
type Editing = SavedStop | 'new' | null;

export function BusPage() {
  const [all, setAll] = useState<BusStops | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<Editing>(null);
  // Settings are open: the stops, and how it buzzes. A stop opened from there goes back there.
  const [settings, setSettings] = useState(false);
  const [apk, setApk] = useState<Downloads['android']>(null);
  const [shift, setShift] = useState<ShiftDay | null>(null);
  // A stop picked on this visit, instead of the one for the shift.
  const [picked, setPicked] = useState<number | null>(null);

  useEffect(() => {
    api.busStops().then(
      (s) => {
        setAll(s);
        if (s.stops.length === 0) setEditing('new');
      },
      (err) => setError(errorMessage(err)),
    );
    api.downloads().then((d) => setApk(d.android), () => {});
    api.shifts().then((m) => setShift(homeShift(m.days, Date.now())), () => {});
  }, []);

  const forShift = all ? stopFor(all, shift) : undefined;
  const stop = all?.stops.find((s) => s.id === picked) ?? forShift ?? all?.stops.find((s) => s.id === all.last_id) ?? all?.stops[0];

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
    webBuzz(true, all?.buzz);
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

  const ready = !!stop;
  const buzzing = practice?.phase === 'buzzing';

  return (
    <section className="stack">
      {ready && (editing || settings) && (
        <button type="button" className="link back" onClick={() => (editing ? setEditing(null) : setSettings(false))}>
          ‹ {editing && settings ? t('Settings') : t('Ride alarm')}
        </button>
      )}
      {(!ready || !editing) && (
        <header className="page-head with-action">
          <div>
            <h1 className="display">{settings && ready ? t('Settings') : t('Ride alarm')}</h1>
            {!(settings && ready) && <p className="subtitle">{t('Buzzes before your stop, so you don’t miss it.')}</p>}
          </div>
          {ready && !settings && !buzzing && (
            <button type="button" className="secondary" onClick={() => setSettings(true)}>
              {t('Settings')}
            </button>
          )}
        </header>
      )}

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

      {all && stop && !buzzing && !editing && !settings && (
        <RideCard
          all={all}
          stop={stop}
          shift={stop === forShift ? shift : null}
          onPick={(s) => {
            setPicked(s.id);
            endPractice();
          }}
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

      {all && !ready && <HowItWorks />}

      {all && editing && (
        <StopForm
          key={editing === 'new' ? 'new' : editing.id}
          stop={editing === 'new' ? null : editing}
          all={all}
          first={!ready}
          onSaved={(s, id) => {
            setAll(s);
            setEditing(null);
            if (s.stops.length === 0) {
              setSettings(false);
              setEditing('new');
            }
            // A stop just added is the one to look at.
            if (id) setPicked(id);
            else if (picked && !s.stops.some((x) => x.id === picked)) setPicked(null);
          }}
        />
      )}

      {all && ready && !editing && settings && <AlarmSettings all={all} onChange={setEditing} onSaved={setAll} />}

      {all && ready && !editing && settings && (
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
function webBuzz(on: boolean, buzz?: Buzz) {
  if (typeof navigator === 'undefined' || !('vibrate' in navigator) || on === vibrating) return;
  vibrating = on;
  try {
    // About 30 seconds of the app's pattern.
    const b = buzzOf(buzz);
    const times = Array.from({ length: 2 * Math.ceil(30_000 / (b.on + b.off)) }, (_, i) => (i % 2 ? b.off : b.on));
    navigator.vibrate(on ? times : 0);
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
          <strong className="block">{t('Start it when you get on')}</strong>
          <span className="muted small">{t('Tap “I’m on the bus” or “I’m on the train”. Then lock the phone and rest.')}</span>
        </span>
      </li>
      <li>
        <span className="how-num" aria-hidden="true">3</span>
        <span>
          <strong className="block">{t('It buzzes before your stop')}</strong>
          <span className="muted small">{t('It keeps buzzing until you tap “Stop”, and adds a sound if you don’t.')}</span>
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

/** The ride as a line: the bus or train moving along it towards the stop, with the stretch where it buzzes. */
function Route({ progress, arrived, kind }: { progress: number; arrived?: boolean; kind: StopKind }) {
  const zone = 24; // % of the line, at the end, where it buzzes
  const at = arrived ? 100 - zone / 2 : Math.min(1, Math.max(0, progress)) * (100 - zone);
  return (
    <div className="route" aria-hidden="true">
      <span className="route-line" />
      <span className="route-done" style={{ width: `${at}%` }} />
      <span className="route-zone" style={{ width: `${zone}%` }} />
      <span className="route-bus" style={{ left: `${at}%` }}>
        <VehicleIcon kind={kind} size={18} stroke={2.4} />
      </span>
      <span className="route-stop" />
    </div>
  );
}

/** A bus or a train, from the front (SamFranIcons.bus and .train on Android). */
export function VehicleIcon({ kind, size = 24, stroke = 2 }: { kind: StopKind; size?: number; stroke?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={kind === 'train' ? trainPath : busPath} />
    </svg>
  );
}
const busPath = 'M8 3h8a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3zM5 11h14M8 21v-3M16 21v-3';
const trainPath = 'M8 2h8a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3zM5 9h14M12 2v7M8.5 13h.01M15.5 13h.01M9 17l-2 4M15 17l2 4';

function RideCard(p: {
  all: BusStops;
  stop: SavedStop;
  /** The shift the stop was picked for, if it was. */
  shift: ShiftDay | null;
  onPick: (s: SavedStop) => void;
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
        <Route progress={1 - p.practiceLeft} kind={stop.kind} />
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
        <span className="eyebrow" id="get-off-at">
          {t('Get off at')}
        </span>
        <div className="stop-chips" role="radiogroup" aria-labelledby="get-off-at">
          {p.all.stops.map((s) => (
            <button key={s.id} type="button" role="radio" aria-checked={s.id === stop.id} className={s.id === stop.id ? 'on' : ''} onClick={() => p.onPick(s)}>
              {s.id === stop.id && <span aria-hidden="true">✓ </span>}
              {s.name || t('Unnamed stop')}
              {s.shifts.length > 0 && <span className="stop-tag">{s.shifts.map(labelOf).join('・')}</span>}
            </button>
          ))}
        </div>
        {p.shift?.end && (
          <p className="stop-why">
            {t('{shift} until {time}, so it’s {stop}.', { shift: labelOf(p.shift.label), time: p.shift.end.slice(11, 16), stop: name })}
          </p>
        )}
        <p className="muted small">
          {t('Buzzes {distance} before the stop.', { distance: km(alert) })} {t(early(alert, stop.kind))}
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

function DistancePicker({ value, kind, onPick, disabled }: { value: number; kind: StopKind; onPick: (m: number) => void; disabled?: boolean }) {
  return (
    <div className="field">
      <span id="buzz-distance">{t('Buzz this far before the stop')}</span>
      <div className="chips" role="radiogroup" aria-labelledby="buzz-distance">
        {distances[kind].map((d) => (
          <button key={d} type="button" role="radio" aria-checked={value === d} className={value === d ? 'on' : ''} disabled={disabled} onClick={() => onPick(d)}>
            {km(d)}
          </button>
        ))}
      </div>
      <span className="hint">
        {t(early(value, kind))} {t(kind === 'train' ? 'On a fast train, choose more.' : 'On a fast road, choose more.')}
      </span>
    </div>
  );
}

function BuzzPicker({ value, onPick, disabled }: { value: Buzz; onPick: (b: Buzz) => void; disabled?: boolean }) {
  return (
    <div className="field">
      <span id="buzz-how">{t('How it buzzes')}</span>
      <div className="chips three" role="radiogroup" aria-labelledby="buzz-how">
        {buzzes.map((b) => (
          <button key={b.buzz} type="button" role="radio" aria-checked={value === b.buzz} className={value === b.buzz ? 'on' : ''} disabled={disabled} onClick={() => onPick(b.buzz)}>
            {t(b.label)}
          </button>
        ))}
      </div>
      <span className="hint">{t(buzzOf(value).about)} {t('Try it now to feel it.')}</span>
    </div>
  );
}

function SoundPicker({ value, onPick, disabled }: { value: number; onPick: (s: number) => void; disabled?: boolean }) {
  return (
    <div className="field">
      <span id="sound-after">{t('If it isn’t stopped, add sound after')}</span>
      <div className="chips four" role="radiogroup" aria-labelledby="sound-after">
        {soundAfters.map((x) => (
          <button key={x.s} type="button" role="radio" aria-checked={value === x.s} className={value === x.s ? 'on' : ''} disabled={disabled} onClick={() => onPick(x.s)}>
            {t(x.label)}
          </button>
        ))}
      </div>
      <span className="hint">
        {value > 0
          ? t('The phone’s alarm sound joins the buzz, getting louder, even on silent. Hear it in the Android app’s settings.')
          : t('It only buzzes.')}
      </span>
    </div>
  );
}

function AlarmSettings(p: { all: BusStops; onChange: (s: Editing) => void; onSaved: (s: BusStops) => void }) {
  const { all } = p;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function setAlarm(s: { buzz?: Buzz; sound_after?: number }) {
    setBusy(true);
    setError('');
    try {
      p.onSaved(await api.setAlarm(s));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="card stack tight">
        <span className="eyebrow">{t('Your stops')}</span>
        <ul className="stop-list">
          {all.stops.map((s) => (
            <li key={s.id}>
              <button type="button" className="stop-row" aria-label={t('Change {stop}', { stop: s.name || t('Unnamed stop') })} onClick={() => p.onChange(s)}>
                <span className="stop-kind" aria-hidden="true">
                  <VehicleIcon kind={s.kind} size={20} />
                </span>
                <span className="grow">
                  <strong className="block">{s.name || t('Unnamed stop')}</strong>
                  {s.shifts.length > 0 && <span className="muted small">{t('After {shifts}', { shifts: s.shifts.map(labelOf).join('・') })}</span>}
                </span>
                <span aria-hidden="true" className="muted">›</span>
              </button>
            </li>
          ))}
        </ul>
        {all.stops.length < maxStops && (
          <button type="button" className="link start" onClick={() => p.onChange('new')}>
            {t('+ Add a stop')}
          </button>
        )}
      </div>
      <div className="card stack">
        <BuzzPicker value={all.buzz} onPick={(b) => b !== all.buzz && void setAlarm({ buzz: b })} disabled={busy} />
        <SoundPicker value={all.sound_after} onPick={(x) => x !== all.sound_after && void setAlarm({ sound_after: x })} disabled={busy} />
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
      </div>
    </>
  );
}

function ShiftPicker({ value, onPick, name, all, id }: { value: ShiftLabel[]; onPick: (v: ShiftLabel[]) => void; name: string; all: BusStops; id?: number }) {
  // Kinds that another stop has now, which this one would take.
  const taken = value
    .map((k) => all.stops.find((s) => s.id !== id && s.shifts.includes(k)))
    .filter((s): s is SavedStop => !!s);
  return (
    <div className="field">
      <span id="stop-shifts">{t('Use this stop after')}</span>
      <div className="chips four" role="group" aria-labelledby="stop-shifts">
        {shiftKinds.map((k) => {
          const on = value.includes(k);
          return (
            <button key={k} type="button" aria-pressed={on} className={on ? 'on' : ''} onClick={() => onPick(on ? value.filter((x) => x !== k) : [...value, k])}>
              {labelOf(k)}
            </button>
          );
        })}
      </div>
      <span className="hint">
        {value.length > 0
          ? t('After these shifts, the alarm picks {stop} for you. You can still pick another stop that day.', { stop: name.trim() || t('this stop') })
          : t('None: it’s only used when you pick it.')}
        {taken.length > 0 && ' ' + t('{stop} won’t be used for them any more.', { stop: [...new Set(taken.map((s) => s.name || t('Unnamed stop')))].join('、') })}
      </span>
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
function StopForm({
  stop,
  all,
  first,
  onSaved,
}: {
  /** null for a new one. */
  stop: SavedStop | null;
  all: BusStops;
  first: boolean;
  /** id: the stop just added. */
  onSaved: (s: BusStops, id?: number) => void;
}) {
  const [name, setName] = useState(stop?.name ?? '');
  const [pos, setPos] = useState<{ lat: number; lng: number } | null>(stop ? { lat: stop.lat, lng: stop.lng } : null);
  const [shifts, setShifts] = useState<ShiftLabel[]>(stop?.shifts ?? []);
  const [kind, setKind] = useState<StopKind>(stop?.kind ?? 'bus');
  const [ask, setAsk] = useState(stop?.ask ?? false);
  // The map shows once a stop is found, until it's saved; or when asked for.
  const [showMap, setShowMap] = useState(false);
  const [link, setLink] = useState('');
  const [byName, setByName] = useState(false);
  // What's being searched for: the name as typed, until a stop is picked.
  const [query, setQuery] = useState('');
  const search = useStopSearch(query, pos);
  const [other, setOther] = useState(false);

  /** A bus stop or a station: the distance changes to suit it, unless it's one of its choices. */
  function changeKind(k: StopKind) {
    setKind(k);
    if (!distances[k].includes(meters)) setMeters(defaultMeters[k]);
  }

  function pick(f: FoundStop) {
    changeKind(f.kind);
    setName(f.name);
    setPos({ lat: f.lat, lng: f.lng });
    setByName(false);
    setShowMap(true);
    setQuery('');
    setError('');
  }
  const [meters, setMeters] = useState(stop?.alert_meters || 800);
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
        setShowMap(true);
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
      const input = { name, lat: pos.lat, lng: pos.lng, alert_meters: meters, shifts, ask, kind };
      if (stop) {
        onSaved(await api.changeBusStop(stop.id, input));
      } else {
        const after = await api.addBusStop(input);
        onSaved(after, Math.max(...after.stops.map((s) => s.id)));
      }
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  async function remove() {
    if (!stop || !confirm(t('Remove {stop}?', { stop: stop.name || t('Unnamed stop') }))) return;
    setBusy(true);
    setError('');
    try {
      onSaved(await api.deleteBusStop(stop.id));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="stack" onSubmit={save}>
      {!first && <h1 className="display">{stop ? stop.name || t('Unnamed stop') : t('Add a stop')}</h1>}
      <div className="card stack">
      {first && <span className="eyebrow">{t('Step 1 of 2')}</span>}
      {first ? <h2>{t('Where do you get off?')}</h2> : <span className="eyebrow">{t('Where')}</span>}
      <div className="field">
        <label htmlFor="stop-name">{t('Stop or station')}</label>
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
              ? t('No stops by that name. Try fewer characters, written as on the sign. For a common name, add the town: 新林 知立.')
              : query && search.found?.length
                ? (
                    <>
                      {t('Tap your stop. Not there? Add the town: 新林 知立.')}{' '}
                      <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">
                        {t('Stops from OpenStreetMap')}
                      </a>
                    </>
                  )
                : null}
        </span>
        {pos && !query && (showMap || first) && <StopMap lat={pos.lat} lng={pos.lng} name={name} />}
        {pos && !query && (
          <span className="found">
            ✓ {byName ? t('Found it on OpenStreetMap, by its name.') : t('Found it.')}{' '}
            {showMap || first ? (
              <a href={`https://www.google.com/maps/search/?api=1&query=${pos.lat},${pos.lng}`} target="_blank" rel="noreferrer">
                {t('Check on the map')}
              </a>
            ) : (
              <button type="button" className="link inline" onClick={() => setShowMap(true)}>
                {t('See it on the map')}
              </button>
            )}
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

      <div className="field">
        <span id="stop-kind">{t('Bus or train')}</span>
        <div className="chips two" role="radiogroup" aria-labelledby="stop-kind">
          {(['bus', 'train'] as const).map((k) => (
            <button key={k} type="button" role="radio" aria-checked={kind === k} className={kind === k ? 'on' : ''} onClick={() => changeKind(k)}>
              {k === 'train' ? t('Station') : t('Bus stop')}
            </button>
          ))}
        </div>
      </div>

      {first && <span className="eyebrow">{t('Step 2 of 2')}</span>}
      <DistancePicker value={meters} kind={kind} onPick={setMeters} />
      </div>
      {!first && (
        <div className="card stack">
          <span className="eyebrow">{t('When it’s used')}</span>
          <ShiftPicker value={shifts} onPick={setShifts} name={name} all={all} id={stop?.id} />
          <div className="field">
            <span id="stop-ask">{t('Ask when these end')}</span>
            <div className="chips two" role="radiogroup" aria-labelledby="stop-ask">
              {[true, false].map((v) => (
                <button key={String(v)} type="button" role="radio" aria-checked={ask === v} className={ask === v ? 'on' : ''} onClick={() => setAsk(v)}>
                  {v ? t('Ask') : t('Don’t ask')}
                </button>
              ))}
            </div>
            <span className="hint">
              {ask ? t('After a shift whose stop this is, the Android app asks “Start the ride alarm?”.') : t('Off: no question after shifts whose stop this is.')}
            </span>
          </div>
        </div>
      )}

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="row end">
        {stop && (
          <button type="button" className="link danger push-start" disabled={busy} onClick={remove}>
            {t('Remove this stop')}
          </button>
        )}
        <button type="submit" disabled={busy || !pos}>
          {t('Save')}
        </button>
      </div>
    </form>
  );
}
