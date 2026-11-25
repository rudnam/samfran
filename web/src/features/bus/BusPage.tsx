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
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { api, errorMessage, type BusStops, type Buzz, type Downloads, type FoundStop, type SavedStop, type ShiftDay, type ShiftLabel, type StopKind } from '../../api';
import { t, tx, type Key } from '../../i18n';
import { BackLink } from '../../components/BackLink';
import { href, navigate, replaceRoute, useRoute } from '../../router';
import { labelOf } from '../shifts/codes';
import { Icon } from '../../components/Icon';
import { confirmDialog } from '../../components/Overlay';
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

/** "Bus stop" or "Station", as each is named beside its green or blue icon. */
function kindName(kind: StopKind): string {
  return kind === 'train' ? t('Station') : t('Bus stop');
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

/** A stop as its page shows it: a saved one, or a new one not added yet. */
type Draft = Omit<SavedStop, 'id'>;

/**
 * What's open besides the ride: Settings; a stop's page (a saved one by id,
 * or a new one before it's added); or the search for where a stop is (for
 * a new one, or to move a saved one).
 */
type View =
  | { page: 'settings' }
  | { page: 'stop'; id: number }
  | { page: 'new'; draft: Draft }
  | { page: 'where'; id: number | 'new' };

/**
 * Each view has its own address (#/bus?view=stop&stop=3), so the browser's
 * and the phone's Back go back through them, as on every other page. A new
 * stop's draft isn't in the address: without it (a reload), its page is the
 * search it came from.
 */
type ViewAddress = { view: 'settings' } | { view: 'stop' | 'where'; stop: number | 'new' } | { view: 'new' };

function viewHref(v: ViewAddress | null): string {
  return v ? href('bus', v) : href('bus');
}

function goTo(v: ViewAddress | null, replace = false) {
  const params = v ?? undefined;
  if (replace) replaceRoute('bus', params);
  else navigate('bus', params);
}

function viewOf(params: URLSearchParams, draft: Draft | null): View | null {
  const stop = params.get('stop');
  const id = stop === 'new' ? 'new' : Number(stop);
  switch (params.get('view')) {
    case 'settings':
      return { page: 'settings' };
    case 'stop':
      return id !== 'new' && id > 0 ? { page: 'stop', id } : { page: 'settings' };
    case 'where':
      return { page: 'where', id: id === 'new' || id > 0 ? id : 'new' };
    case 'new':
      return draft ? { page: 'new', draft } : { page: 'where', id: 'new' };
    default:
      return null;
  }
}

/** A new stop at a place picked: the usual distance for its kind, a strong buzz, no shifts. */
function draftAt(p: Picked): Draft {
  return { ...p, alert_meters: defaultMeters[p.kind], buzz: 'strong', sound_after: 60, shifts: [], ask: false };
}

export function BusPage() {
  const [all, setAll] = useState<BusStops | null>(null);
  const [error, setError] = useState('');
  const route = useRoute();
  // A new stop being set up, before it's added.
  const [draft, setDraft] = useState<Draft | null>(null);
  const view = viewOf(route.params, draft);
  const [apk, setApk] = useState<Downloads['android']>(null);
  const [shift, setShift] = useState<ShiftDay | null>(null);
  // A stop picked on this visit, instead of the one for the shift.
  const [picked, setPicked] = useState<number | null>(null);
  // The stop just added, marked in Settings.
  const [added, setAdded] = useState<number | null>(null);

  useEffect(() => {
    api.busStops().then(setAll, (err) => setError(errorMessage(err)));
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
    webBuzz(true, stop?.buzz);
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
  const first = all !== null && !ready;

  /** A change that didn't save: back to what the server has. */
  function undo() {
    api.busStops().then(setAll, () => {});
  }

  /** Saves a change to a saved stop, straight away. */
  async function change(s: SavedStop, c: Partial<Draft>) {
    setError('');
    const next = { ...s, ...c };
    // Shown at once (a switch flips as it's tapped); the server's answer then replaces it.
    setAll((a) => a && { ...a, stops: a.stops.map((x) => (x.id === s.id ? next : x)) });
    try {
      setAll(await api.changeBusStop(s.id, { name: next.name, lat: next.lat, lng: next.lng, alert_meters: next.alert_meters, kind: next.kind, buzz: next.buzz, sound_after: next.sound_after, shifts: next.shifts, ask: next.ask }));
    } catch (err) {
      setError(errorMessage(err));
      undo();
    }
  }

  async function add(d: Draft) {
    setError('');
    try {
      const after = await api.addBusStop(d);
      const id = Math.max(...after.stops.map((s) => s.id));
      setAll(after);
      setDraft(null);
      if (first) {
        goTo(null, true);
      } else {
        setAdded(id);
        goTo({ view: 'settings' }, true);
      }
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function remove(s: SavedStop) {
    if (!(await confirmDialog({ title: t('Remove {stop}?', { stop: s.name || t('Unnamed stop') }), confirm: t('Remove'), danger: true }))) return;
    setError('');
    try {
      const after = await api.deleteBusStop(s.id);
      setAll(after);
      if (picked === s.id) setPicked(null);
      goTo(after.stops.length ? { view: 'settings' } : null, true);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const editing = view?.page === 'stop' ? all?.stops.find((s) => s.id === view.id) : undefined;
  const moving = view?.page === 'where' && view.id !== 'new' ? all?.stops.find((s) => s.id === view.id) : undefined;
  // The way back from each page, named after where it goes.
  const back: { label: Key; text?: string; to: ViewAddress | null } | null = !view
    ? null
    : view.page === 'settings'
      ? { label: 'Ride alarm', to: null }
      : view.page === 'stop'
        ? { label: 'Settings', to: { view: 'settings' } }
        : view.page === 'new'
          ? { label: 'Add a stop', to: { view: 'where', stop: 'new' } }
          : view.id === 'new'
            ? first
              ? null
              : { label: 'Settings', to: { view: 'settings' } }
            : { label: 'Back', text: moving?.name || undefined, to: { view: 'stop', stop: view.id } };
  // The first time, choosing the stop is the page.
  const shown: View | null = first && !view ? { page: 'where', id: 'new' } : view;

  return (
    <section className="stack">
      {back && <BackLink href={viewHref(back.to)} label={back.label} text={back.text} />}
      {(!shown || first) && (
        <header className="page-head with-action">
          <div>
            <h1 className="display">{t('Ride alarm')}</h1>
            <p className="subtitle">{t('Buzzes before your stop, so you don’t miss it.')}</p>
          </div>
          {ready && !buzzing && (
            <a className="icon-button" href={viewHref({ view: 'settings' })} aria-label={t('Settings')} title={t('Settings')}>
              <Icon name="settings" />
            </a>
          )}
        </header>
      )}

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {!shown && stop && buzzing && (
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

      {!shown && all && stop && !buzzing && (
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

      {first && !view && <HowItWorks />}

      {all && shown?.page === 'settings' && (
        <SettingsPage all={all} added={added} onOpen={(id) => goTo(id === 'new' ? { view: 'where', stop: 'new' } : { view: 'stop', stop: id })} />
      )}

      {all && shown?.page === 'where' && (
        <WherePage
          key={String(shown.id)}
          first={first}
          title={moving ? moving.name || t('Unnamed stop') : t('Add a stop')}
          near={moving ? { lat: moving.lat, lng: moving.lng } : all.stops[0] ? { lat: all.stops[0].lat, lng: all.stops[0].lng } : null}
          onPick={(p) => {
            if (moving) {
              const meters = distances[p.kind].includes(moving.alert_meters) ? moving.alert_meters : defaultMeters[p.kind];
              void change(moving, { ...p, alert_meters: meters });
              goTo({ view: 'stop', stop: moving.id }, true);
            } else {
              setDraft(draftAt(p));
              goTo({ view: 'new' });
            }
          }}
        />
      )}

      {all && shown?.page === 'new' && (
        <StopPage
          stop={shown.draft}
          all={all}
          onChange={(c) => setDraft({ ...shown.draft, ...c })}
          onWhere={() => goTo({ view: 'where', stop: 'new' })}
          onAdd={() => add(shown.draft)}
        />
      )}

      {all && editing && (
        <StopPage
          key={editing.id}
          stop={editing}
          id={editing.id}
          all={all}
          onChange={(c) => change(editing, c)}
          onWhere={() => goTo({ view: 'where', stop: editing.id })}
          onRemove={() => remove(editing)}
        />
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

/** A bus or a train, from the front: the bus boxy, with its mirrors (SamFranIcons.bus and .train on Android). */
export function VehicleIcon({ kind, size = 24, stroke = 2 }: { kind: StopKind; size?: number; stroke?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={kind === 'train' ? trainPath : busPath} />
    </svg>
  );
}
const busPath = 'M7 3h10a2 2 0 0 1 2 2v13H5V5a2 2 0 0 1 2-2zM5 7h14M5 13h14M3 8v3M21 8v3M8 16h.01M16 16h.01M7 18v3M17 18v3';
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
              <span className={`kind-dot ${s.kind}`} role="img" aria-label={kindName(s.kind)}>
                <VehicleIcon kind={s.kind} size={14} />
              </span>
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

/** A row that opens something: what it is, and what it's set to now. */
function Row({ icon, label, value, onClick }: { icon: ReactNode; label: string; value: string; onClick: () => void }) {
  return (
    <button type="button" className="setting-row" onClick={onClick}>
      <span className="setting-icon" aria-hidden="true">
        {icon}
      </span>
      <span className="grow setting-label">{label}</span>
      <span className="setting-value">{value}</span>
      <span aria-hidden="true" className="muted">
        ›
      </span>
    </button>
  );
}

/** One choice, opened from a row: from the bottom on phones, in the middle on wider screens. */
function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialog.current?.showModal();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="ask"
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === dialog.current) onClose();
      }}
    >
      <div className="stack">
        <h2>{title}</h2>
        {children}
      </div>
    </dialog>
  );
}

/** One-tap choices in a row: picking one is the answer. */
function Choices<T extends string | number>({ label, choices, value, onPick, className }: { label: string; choices: { v: T; text: string }[]; value: T; onPick: (v: T) => void; className?: string }) {
  return (
    <div className={`chips ${className ?? ''}`} role="radiogroup" aria-label={label}>
      {choices.map((c) => (
        <button key={String(c.v)} type="button" role="radio" aria-checked={value === c.v} className={value === c.v ? 'on' : ''} onClick={() => onPick(c.v)}>
          {c.text}
        </button>
      ))}
    </div>
  );
}

/** Settings: the stops (each opens its page), and how it works. */
function SettingsPage(p: { all: BusStops; added: number | null; onOpen: (id: number | 'new') => void }) {
  const { all } = p;
  return (
    <>
      <h1 className="display">{t('Settings')}</h1>
      <div className="card stack tight">
        <span className="eyebrow">{t('Your stops')}</span>
        <ul className="stop-list">
          {all.stops.map((s) => (
            <li key={s.id} className={s.id === p.added ? 'just-added' : undefined}>
              <button type="button" className="stop-row" aria-label={t('Change {stop}', { stop: s.name || t('Unnamed stop') })} onClick={() => p.onOpen(s.id)}>
                <span className={`kind-tile ${s.kind}`} aria-hidden="true">
                  <VehicleIcon kind={s.kind} size={22} />
                </span>
                <span className="grow">
                  <strong className="block">{s.name || t('Unnamed stop')}</strong>
                  <span className="small">
                    <span className={`kind-name ${s.kind}`}>{kindName(s.kind)}</span>
                    {s.id === p.added ? (
                      <span className="muted"> · {t('Just added')}</span>
                    ) : (
                      s.shifts.length > 0 && <span className="muted"> · {t('After {shifts}', { shifts: s.shifts.map(labelOf).join('・') })}</span>
                    )}
                  </span>
                </span>
                <span aria-hidden="true" className="muted">
                  ›
                </span>
              </button>
            </li>
          ))}
        </ul>
        {all.stops.length < maxStops && (
          <button type="button" className="link start" onClick={() => p.onOpen('new')}>
            {t('+ Add a stop')}
          </button>
        )}
      </div>
      <details className="card how-fold">
        <summary>{t('How it works')}</summary>
        <Steps />
        <p className="muted small">
          {tx('Stops and stations found by name come from {osm}.', {
            osm: (
              <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">
                OpenStreetMap
              </a>
            ),
          })}
        </p>
      </details>
    </>
  );
}

/** Which sheet is open on a stop's page. */
type Open = 'distance' | 'buzz' | 'shifts' | null;

/**
 * A stop's page: a small map, then one row for each setting, each opening
 * its choices. A saved stop saves each change as it's made; a new one is
 * added by "Add stop", so backing out leaves nothing behind.
 */
function StopPage(p: {
  stop: Draft;
  /** A saved stop's id; none for a new one. */
  id?: number;
  all: BusStops;
  onChange: (c: Partial<Draft>) => void;
  onWhere: () => void;
  onAdd?: () => Promise<void>;
  onRemove?: () => void;
}) {
  const { stop } = p;
  const [open, setOpen] = useState<Open>(null);
  const [busy, setBusy] = useState(false);
  const name = stop.name || t('Unnamed stop');
  const pick = (c: Partial<Draft>) => {
    setOpen(null);
    p.onChange(c);
  };
  return (
    <>
      <h1 className="display">{name}</h1>
      <div className="card">
        <StopMap lat={stop.lat} lng={stop.lng} name={stop.name} small />
      </div>
      <div className="card setting-list">
        <Row icon={<VehicleIcon kind={stop.kind} size={20} />} label={t('Where')} value={name} onClick={p.onWhere} />
        <Row icon={<RulerIcon />} label={t('Buzz before')} value={km(stop.alert_meters)} onClick={() => setOpen('distance')} />
        <Row icon={<BellIcon />} label={t('How it buzzes')} value={t(buzzOf(stop.buzz).label)} onClick={() => setOpen('buzz')} />
        <label className="switch setting-switch">
          <span className="grow">
            <span className="block setting-label">{t('Add sound if I don’t stop it')}</span>
            <span className="muted small">{t('After a minute of buzzing, the phone’s alarm sound plays too, getting louder. Even on silent.')}</span>
          </span>
          <input type="checkbox" role="switch" checked={stop.sound_after > 0} onChange={(e) => p.onChange({ sound_after: e.target.checked ? 60 : 0 })} />
        </label>
      </div>
      <div className="card setting-list">
        <Row icon={<CalendarIcon />} label={t('Use after')} value={stop.shifts.length ? stop.shifts.map(labelOf).join('・') : t('None')} onClick={() => setOpen('shifts')} />
        <label className="switch setting-switch">
          <span className="grow setting-label">{t('Ask when these end')}</span>
          <input type="checkbox" role="switch" checked={stop.ask} onChange={(e) => p.onChange({ ask: e.target.checked })} />
        </label>
      </div>
      {p.onAdd ? (
        <button
          type="button"
          className="big"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            await p.onAdd!();
            setBusy(false);
          }}
        >
          {t('Add stop')}
        </button>
      ) : (
        <button type="button" className="link danger center" onClick={p.onRemove}>
          {t('Remove this stop')}
        </button>
      )}

      {open === 'distance' && (
        <Sheet title={t('Buzz before {stop}', { stop: name })} onClose={() => setOpen(null)}>
          <Choices label={t('Buzz before')} choices={distances[stop.kind].map((d) => ({ v: d, text: km(d) }))} value={stop.alert_meters} onPick={(d) => pick({ alert_meters: d })} />
          <p className="hint">{t(early(stop.alert_meters, stop.kind))}</p>
        </Sheet>
      )}
      {open === 'buzz' && (
        <Sheet title={t('How it buzzes at {stop}', { stop: name })} onClose={() => setOpen(null)}>
          <Choices label={t('How it buzzes')} className="three" choices={buzzes.map((b) => ({ v: b.buzz, text: t(b.label) }))} value={stop.buzz} onPick={(b) => pick({ buzz: b })} />
          <p className="hint">{t(buzzOf(stop.buzz).about)}</p>
        </Sheet>
      )}
      {open === 'shifts' && <ShiftSheet stop={stop} id={p.id} all={p.all} onDone={(shifts) => pick({ shifts })} onClose={() => setOpen(null)} />}
    </>
  );
}

/** The kinds of shift a stop is for: several can be on, so it has a Done. */
function ShiftSheet({ stop, id, all, onDone, onClose }: { stop: Draft; id?: number; all: BusStops; onDone: (v: ShiftLabel[]) => void; onClose: () => void }) {
  const [value, setValue] = useState(stop.shifts);
  // Kinds that another stop has now, which this one would take.
  const taken = [...new Set(value.map((k) => all.stops.find((s) => s.id !== id && s.shifts.includes(k))).filter((s): s is SavedStop => !!s))];
  return (
    <Sheet title={t('Use {stop} after', { stop: stop.name || t('this stop') })} onClose={onClose}>
      <div className="chips four" role="group" aria-label={t('Use after')}>
        {shiftKinds.map((k) => {
          const on = value.includes(k);
          return (
            <button key={k} type="button" aria-pressed={on} className={on ? 'on' : ''} onClick={() => setValue(on ? value.filter((x) => x !== k) : shiftKinds.filter((x) => x === k || value.includes(x)))}>
              {labelOf(k)}
            </button>
          );
        })}
      </div>
      {taken.length > 0 && <p className="hint">{t('{stop} won’t be used for them any more.', { stop: taken.map((s) => s.name || t('Unnamed stop')).join('、') })}</p>}
      <button type="button" onClick={() => onDone(value)}>
        {t('Done')}
      </button>
    </Sheet>
  );
}

/** Where a stop is: a name, a place, and whether it's a bus stop or a station. */
type Picked = { name: string; lat: number; lng: number; kind: StopKind };

/** A search result as a stop: without its area, which isn't kept. */
function pickOf(f: FoundStop): Picked {
  return { name: f.name, lat: f.lat, lng: f.lng, kind: f.kind };
}

/**
 * Where a stop is: search by name (and town), each result's icon saying
 * bus stop or station; or, folded away, a Google Maps link to it.
 */
function WherePage({ first, title, near, onPick }: { first: boolean; title: string; near: { lat: number; lng: number } | null; onPick: (p: Picked) => void }) {
  const [query, setQuery] = useState('');
  const search = useStopSearch(query, near);
  const [link, setLink] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // From a link: where it is, but not its name or kind for sure, so those are asked.
  const [fromLink, setFromLink] = useState<Picked | null>(null);

  async function useLink() {
    if (!link.trim()) return;
    setBusy(true);
    setError('');
    try {
      const p = await api.lookupMap(link.trim(), true);
      if (p.lat === undefined || p.lng === undefined) {
        setError(t('Couldn’t find where that stop is. In Google Maps, press and hold on the stop to drop a pin, then share that pin’s link.'));
      } else {
        // Google Maps calls a stop "Name（バス）".
        setFromLink({ name: (p.name ?? query).replace(/\s*[（(](バス|Bus)[）)]$/, ''), lat: p.lat, lng: p.lng, kind: 'bus' });
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {first ? <h2>{t('Where do you get off?')}</h2> : <h1 className="display">{title}</h1>}
      <div className="card stack">
        <input
          type="search"
          autoFocus={!first}
          maxLength={80}
          autoComplete="off"
          aria-label={t('Stop or station')}
          placeholder={t('Name, and the town if it’s common')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && search.found?.length) onPick(pickOf(search.found[0]));
          }}
        />
        {query && search.found && search.found.length > 0 && (
          <ul className="found-list" aria-label={t('Matching stops')}>
            {search.found.map((f) => (
              <li key={`${f.name}${f.lat}${f.lng}`}>
                <button type="button" onClick={() => onPick(pickOf(f))}>
                  <span className={`kind-tile ${f.kind}`} aria-hidden="true">
                    <VehicleIcon kind={f.kind} size={22} />
                  </span>
                  <span className="grow">
                    <strong className="block">{f.name}</strong>
                    <span className="small">
                      <span className={`kind-name ${f.kind}`}>{kindName(f.kind)}</span>
                      {f.area && <span className="muted"> · {f.area}</span>}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {query && search.failed && <p className="hint">{t('The stop search isn’t answering. Paste a Google Maps link instead.')}</p>}
        {query && search.found?.length === 0 && <p className="hint">{t('No stops by that name. Try fewer characters, written as on the sign. For a common name, add the town: 新林 知立.')}</p>}
        <details className="other-ways">
          <summary>{t('Can’t find it? Other ways')}</summary>
          <div className="stack tight">
            <span className="row">
              <input type="url" aria-label={t('Google Maps link to the stop')} placeholder={t('Paste a Google Maps link to the stop')} value={link} onChange={(e) => setLink(e.target.value)} />
              <button type="button" className="secondary" disabled={busy || !link.trim()} onClick={useLink}>
                {t('Use link')}
              </button>
            </span>
            {fromLink && (
              <div className="stack tight">
                <StopMap lat={fromLink.lat} lng={fromLink.lng} name={fromLink.name} small />
                <label className="field">
                  <span>{t('Name')}</span>
                  <input maxLength={80} value={fromLink.name} onChange={(e) => setFromLink({ ...fromLink, name: e.target.value })} />
                </label>
                <Choices
                  label={t('Bus or train')}
                  className="two"
                  choices={[
                    { v: 'bus' as StopKind, text: t('Bus stop') },
                    { v: 'train' as StopKind, text: t('Station') },
                  ]}
                  value={fromLink.kind}
                  onPick={(k) => setFromLink({ ...fromLink, kind: k })}
                />
                <button type="button" onClick={() => onPick(fromLink)}>
                  {t('Use this')}
                </button>
              </div>
            )}
          </div>
        </details>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
      </div>
    </>
  );
}

function RulerIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 12h16M4 8v8M20 8v8M8 12v-2M12 12v-3M16 12v-2" />
    </svg>
  );
}

function BellIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 9a6 6 0 0 1 12 0c0 6 2 7 2 7H4s2-1 2-7zM10 20a2 2 0 0 0 4 0" />
    </svg>
  );
}

function CalendarIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM3 10h18M8 3v4M16 3v4" />
    </svg>
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
