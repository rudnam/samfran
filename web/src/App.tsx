import { useEffect, useState } from 'react';
import { api, ApiError, errorMessage, type Me } from './api';
import { AuthForm } from './components/AuthForm';
import { LanguagePicker } from './components/LanguagePicker';
import { ResetPasswordForm } from './components/ResetPasswordForm';
import { clearCache } from './cache';
import { Overlays } from './components/Overlay';
import { BusPage } from './features/bus/BusPage';
import { ConnectPage, rememberConnect, resumeConnect } from './features/connect/ConnectPage';
import { RestaurantDetail } from './features/restaurants/RestaurantDetail';
import { RestaurantForm } from './features/restaurants/RestaurantForm';
import { RestaurantsPage } from './features/restaurants/RestaurantsPage';
import { ShiftEditor } from './features/shifts/ShiftEditor';
import { ShiftSettingsPage } from './features/shifts/ShiftSettingsPage';
import { ShiftCalendarLinkPage } from './features/shifts/ShiftCalendarLinkPage';
import { ShiftsPage } from './features/shifts/ShiftsPage';
import { UsPage } from './features/us/UsPage';
import { useUnseen } from './features/us/unseen';
import { t, tn, tx, useLanguage } from './i18n';
import { initialNotice, type Notice } from './notice';
import { takeSharedFromUrl } from './share';
import { href, navigate, replaceRoute, useRoute, type Route } from './router';
import { SpaceProvider } from './space';
import { ErrorText } from './components/ErrorText';

/**
 * The introduction page's "Try the demo" opens /app/?demo. Read once when
 * the app loads, then dropped from the address so a reload doesn't start
 * another demo.
 */
const demoRequested = (() => {
  const params = new URLSearchParams(location.search);
  if (!params.has('demo')) return false;
  params.delete('demo');
  const qs = params.toString();
  history.replaceState(history.state, '', location.pathname + (qs ? `?${qs}` : '') + location.hash);
  return true;
})();

/** Text shared to the installed web app when it opened (share_target). */
const sharedAtLaunch = takeSharedFromUrl();

type Session =
  | { status: 'loading'; demo?: boolean }
  | { status: 'signed-out'; notice?: Notice | null; mode?: 'sign-up' }
  | { status: 'signed-in'; me: Me; notice?: Notice | null }
  | { status: 'error'; message: string };

export function App() {
  const [session, setSession] = useState<Session>({ status: 'loading' });
  const route = useRoute();
  useLanguage(); // redraw everything when the language changes

  // Signing in with Google comes back to /app/, without the Connect request.
  useEffect(() => {
    if (session.status === 'signed-in') resumeConnect();
  }, [session.status]);

  // A place shared from Google Maps to the installed web app: open Add a
  // place with it filled in, once signed in.
  const [shared, setShared] = useState<string | null>(sharedAtLaunch);
  useEffect(() => {
    if (shared && session.status === 'signed-in') {
      navigate('add', { shared });
      setShared(null);
    }
  }, [shared, session.status]);

  // What one person loaded isn't the next one's to see.
  useEffect(() => {
    clearCache();
  }, [session.status]);

  useEffect(() => {
    api
      .me()
      // e.g. "Your email address is confirmed", after opening the emailed link.
      .then((me) => setSession({ status: 'signed-in', me, notice: initialNotice }))
      .catch((err) => {
        if (!(err instanceof ApiError && err.status === 401)) {
          setSession({ status: 'error', message: errorMessage(err) });
          return;
        }
        // Claude asking to connect waits for the person to sign in.
        rememberConnect();
        const connecting = location.hash.startsWith('#/connect?');
        if (demoRequested && !connecting) {
          startDemo();
          return;
        }
        const join = joinNotice();
        if (join) {
          setSession({ status: 'signed-out', mode: join.ok ? 'sign-up' : undefined, notice: join });
          return;
        }
        setSession({
          status: 'signed-out',
          notice: initialNotice ?? (connecting ? { message: t('Sign in to connect Claude to SamFran.'), ok: true } : null),
        });
      });
  }, []);

  /** Signs in to a new demo account full of sample data. */
  async function startDemo() {
    setSession({ status: 'loading', demo: true });
    try {
      setSession({ status: 'signed-in', me: await api.startDemo() });
    } catch (err) {
      setSession({ status: 'signed-out', notice: { message: errorMessage(err), ok: false } });
    }
  }

  /** From a demo to the sign-up form. Signing out deletes the demo. */
  async function leaveDemo() {
    await api.signOut();
    replaceRoute('home'); // whoever signs in next starts at the list
    setSession({ status: 'signed-out', mode: 'sign-up' });
  }

  async function signOut() {
    await api.signOut();
    replaceRoute('home');
    setSession({ status: 'signed-out' });
  }

  // The emailed reset link, whether or not this browser is signed in.
  const resetting = route.page === 'reset' && (session.status === 'signed-in' || session.status === 'signed-out');

  if (!resetting && session.status === 'signed-in') {
    const { me, notice } = session;
    const tab = route.page === 'us' ? 'us' : route.page === 'bus' ? 'bus' : route.page.startsWith('shifts') ? 'shifts' : 'food';
    return (
      <SpaceProvider>
        <Overlays />
        <div className="app">
          <header className="appbar">
            <a className="brand" href={href('home')}>
              <img className="logo" src="/icon.svg" alt="" width="30" height="30" />
              SamFran
            </a>
            <nav className="tabbar" aria-label={t('Sections')}>
              <a href={href('home')} className={tab === 'food' ? 'active' : ''} aria-current={tab === 'food' ? 'page' : undefined}>
                <PlacesIcon />
                {t('Places')}
              </a>
              <a href={href('shifts')} className={tab === 'shifts' ? 'active' : ''} aria-current={tab === 'shifts' ? 'page' : undefined}>
                <ShiftsIcon />
                {t('Shifts')}
              </a>
              <a href={href('bus')} className={tab === 'bus' ? 'active' : ''} aria-current={tab === 'bus' ? 'page' : undefined}>
                <RideIcon />
                {t('Ride')}
              </a>
              {/* Named by hand, so the dot's own label isn't read as part of the tab's name. */}
              <a href={href('us')} aria-label={t('Us')} className={tab === 'us' ? 'active' : ''} aria-current={tab === 'us' ? 'page' : undefined}>
                <UsIcon />
                {t('Us')}
                <UnseenDot />
              </a>
            </nav>
          </header>
          <main className="page with-tabs">
            <div className="stack">
              {notice && (
                <p role={notice.ok ? 'status' : 'alert'} className={notice.ok ? 'notice' : 'notice error'}>
                  {notice.message}
                </p>
              )}
              {me.demo && <DemoBanner onCreateAccount={leaveDemo} />}
              {me.confirm_email && <ConfirmEmail email={me.email} />}
              {/* A new page fades up into place; switching between the two lists doesn't. */}
              <div className="enter" key={enterKey(route)}>
                <Page route={route} me={me} onSignOut={signOut} />
              </div>
            </div>
          </main>
        </div>
      </SpaceProvider>
    );
  }

  return (
    <>
      <header className="topbar">
        <a className="brand" href="/">
          <img className="logo" src="/icon.svg" alt="" width="30" height="30" />
          SamFran
        </a>
        <LanguagePicker />
      </header>
      <main className="page narrow enter">
        {session.status === 'loading' && (
          <p className="muted">{session.demo ? t('Setting up your demo…') : t('Loading…')}</p>
        )}
        {session.status === 'error' && (
          <ErrorText message={t('Could not reach the server: {error}', { error: session.message })} />
        )}
        {resetting && (
          <ResetPasswordForm
            token={route.params.get('token') ?? ''}
            onDone={(me) => {
              // Off the reset page, without leaving the used link in the history.
              replaceRoute('home');
              setSession({ status: 'signed-in', me, notice: { message: t('Your new password is saved.'), ok: true } });
            }}
            onCancel={() => replaceRoute('home')}
          />
        )}
        {!resetting && session.status === 'signed-out' && (
          <AuthForm
            key={`${session.mode}-${session.notice?.message}`}
            mode={session.mode}
            notice={session.notice}
            onSignedIn={(me) => setSession({ status: 'signed-in', me })}
            onDemo={startDemo}
          />
        )}
      </main>
    </>
  );
}

/** The signed-in page for the address. */
function Page({ route, me, onSignOut }: { route: Route; me: Me; onSignOut: () => void }) {
  const id = Number(route.params.get('id'));
  switch (route.page) {
    case 'want':
      return <RestaurantsPage tab="want" />;
    case 'restaurant':
      return id > 0 ? <RestaurantDetail key={id} id={id} /> : <RestaurantsPage tab="been" />;
    case 'add':
      return (
        <RestaurantForm key={route.params.toString()} want={route.params.has('want')} shared={route.params.get('shared') ?? undefined} />
      );
    case 'edit':
      return id > 0 ? <RestaurantForm key={id} id={id} /> : <RestaurantsPage tab="been" />;
    case 'shifts':
      // Not keyed by month: another month changes the calendar, not the whole page.
      return <ShiftsPage month={route.params.get('month') ?? undefined} day={route.params.get('day') ?? undefined} />;
    case 'shifts/edit': {
      const month = route.params.get('month') ?? '';
      return /^\d{4}-\d{2}$/.test(month) ? (
        <ShiftEditor key={route.params.toString()} month={month} fromPhoto={route.params.get('from') === 'photo'} />
      ) : (
        <ShiftsPage />
      );
    }
    case 'shifts/settings':
      return <ShiftSettingsPage />;
    case 'shifts/calendar':
      return <ShiftCalendarLinkPage />;
    case 'bus':
      return <BusPage />;
    case 'connect':
      return <ConnectPage route={route} email={me.email} />;
    case 'us':
      return <UsPage me={me} onSignOut={onSignOut} />;
    default:
      return <RestaurantsPage tab="been" />;
  }
}

/** Which page is showing, for its entrance: the same for both lists and for every month of shifts. */
function enterKey(route: Route): string {
  if (route.page === 'want') return 'home';
  return route.page + (route.params.get('id') ?? '');
}

/** On the Us tab: the other person changed something since this device last looked. */
function UnseenDot() {
  const n = useUnseen();
  if (n === 0) return null;
  return <span className="tab-dot" role="img" aria-label={tn(n, '{n} new change', '{n} new changes')} />;
}

function PlacesIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21zM14.5 9.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z" />
    </svg>
  );
}

function ShiftsIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="5" width="18" height="16" rx="3" />
      <path d="M3 10h18M8 3v4M16 3v4" />
    </svg>
  );
}

/** The ride alarm's tab, for the bus or the train: a route from where you get on to where you get off. */
function RideIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="6" cy="19" r="2.5" />
      <path d="M8.5 19H17a3.5 3.5 0 0 0 0-7H7a3.5 3.5 0 0 1 0-7h8.5" />
      <path d="M18 9s-3-2.7-3-5a3 3 0 0 1 6 0c0 2.3-3 5-3 5z" />
    </svg>
  );
}

function UsIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="9" cy="8" r="3.5" />
      <circle cx="17" cy="9" r="2.5" />
      <path d="M3 20c0-3.5 2.7-6 6-6s6 2.5 6 6M15 14.5c3 0 6 1.8 6 5.5" />
    </svg>
  );
}

/** On the page an invite link opens (#/join): who invited them, or why the link didn't work. */
function joinNotice(): Notice | null {
  const m = location.hash.match(/^#\/join\??(.*)$/);
  if (!m) return null;
  const p = new URLSearchParams(m[1]);
  history.replaceState(history.state, '', location.pathname); // off the join page once read
  if (p.has('invalid')) return { message: t('That invite link has expired or was already used. Ask for a new one.'), ok: false };
  const from = p.get('from');
  return {
    message: from ? t('{name} invited you to SamFran. Create your account to join.', { name: from }) : t('You’re invited to SamFran. Create your account to join.'),
    ok: true,
  };
}

/** In a demo: what this is, and the way to an account of one's own. */
function DemoBanner({ onCreateAccount }: { onCreateAccount: () => void }) {
  return (
    <div className="notice row" role="note">
      <span className="grow">
        <strong>{t('This is a demo with sample data.')}</strong>{' '}
        {t('Change anything you like: nobody else sees it, and it’s deleted within a day.')}
      </span>
      <button type="button" className="secondary" onClick={onCreateAccount}>
        {t('Create your account')}
      </button>
    </div>
  );
}

/** A reminder to open the link we emailed, with a way to send it again. */
function ConfirmEmail({ email }: { email: string }) {
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | string>('idle');
  async function resend() {
    setState('sending');
    try {
      await api.resendConfirmation();
      setState('sent');
    } catch (err) {
      setState(errorMessage(err));
    }
  }
  return (
    <div className="notice row">
      <span className="grow">
        {state === 'sent'
          ? tx('Sent. Check your inbox at {email}.', { email: <strong>{email}</strong> })
          : tx('Confirm your email: open the link we sent to {email}.', { email: <strong>{email}</strong> })}
        {state !== 'idle' && state !== 'sending' && state !== 'sent' && <span className="error block">{state}</span>}
      </span>
      {state !== 'sent' && (
        <button type="button" className="link" disabled={state === 'sending'} onClick={resend}>
          {t('Send again')}
        </button>
      )}
    </div>
  );
}
