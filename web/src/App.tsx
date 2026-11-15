import { useEffect, useState } from 'react';
import { api, ApiError, errorMessage, type Me } from './api';
import { AuthForm } from './components/AuthForm';
import { LanguagePicker } from './components/LanguagePicker';
import { ResetPasswordForm } from './components/ResetPasswordForm';
import { RestaurantDetail } from './features/restaurants/RestaurantDetail';
import { RestaurantForm } from './features/restaurants/RestaurantForm';
import { RestaurantsPage } from './features/restaurants/RestaurantsPage';
import { ShiftEditor } from './features/shifts/ShiftEditor';
import { ShiftSettingsPage } from './features/shifts/ShiftSettingsPage';
import { ShiftsPage } from './features/shifts/ShiftsPage';
import { UsPage } from './features/us/UsPage';
import { t, tx, useLanguage } from './i18n';
import { onAuthLinks, onSharedText } from './native';
import { initialNotice, signinErrors, type Notice } from './notice';
import { takeSharedFromUrl } from './share';
import { useUpdate } from './updates';
import { href, navigate, replaceRoute, useRoute, type Route } from './router';
import { SpaceProvider } from './space';

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
  history.replaceState(null, '', location.pathname + (qs ? `?${qs}` : '') + location.hash);
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

  // In the Android app: Google sign-in coming back from the phone's browser.
  useEffect(
    () =>
      onAuthLinks((result) => {
        if ('error' in result) {
          setSession({ status: 'signed-out', notice: { message: t(signinErrors[result.error] ?? signinErrors.failed), ok: false } });
          return;
        }
        api.appToken(result.code, result.verifier).then(
          (me) => setSession({ status: 'signed-in', me }),
          (err) => setSession({ status: 'signed-out', notice: { message: errorMessage(err), ok: false } }),
        );
      }),
    [],
  );

  // A place shared from Google Maps (the Android app, or the installed web
  // app): open Add a place with it filled in, once signed in.
  const [shared, setShared] = useState<string | null>(sharedAtLaunch);
  useEffect(() => onSharedText(setShared), []);
  useEffect(() => {
    if (shared && session.status === 'signed-in') {
      navigate('add', { shared });
      setShared(null);
    }
  }, [shared, session.status]);

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
        if (demoRequested) {
          startDemo();
          return;
        }
        setSession({ status: 'signed-out', notice: initialNotice });
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
    const tab = route.page === 'us' ? 'us' : route.page.startsWith('shifts') ? 'shifts' : 'food';
    return (
      <SpaceProvider>
        <div className="app">
          <header className="appbar">
            <a className="brand" href={href('home')}>
              samfran
            </a>
            <nav className="tabbar" aria-label={t('Sections')}>
              <a href={href('home')} className={tab === 'food' ? 'active' : ''} aria-current={tab === 'food' ? 'page' : undefined}>
                <FoodIcon />
                {t('Food')}
              </a>
              <a href={href('shifts')} className={tab === 'shifts' ? 'active' : ''} aria-current={tab === 'shifts' ? 'page' : undefined}>
                <ShiftsIcon />
                {t('Shifts')}
              </a>
              <a href={href('us')} className={tab === 'us' ? 'active' : ''} aria-current={tab === 'us' ? 'page' : undefined}>
                <UsIcon />
                {t('Us')}
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
              <UpdateBanner />
              <Page route={route} me={me} onSignOut={signOut} />
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
          samfran
        </a>
        <LanguagePicker />
      </header>
      <main className="page narrow">
        {session.status === 'loading' && (
          <p className="muted">{session.demo ? t('Setting up your demo…') : t('Loading…')}</p>
        )}
        {session.status === 'error' && (
          <p role="alert" className="error">
            {t('Could not reach the server: {error}', { error: session.message })}
          </p>
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
      return <ShiftsPage key={route.params.get('month') ?? ''} month={route.params.get('month') ?? undefined} />;
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
    case 'us':
      return <UsPage me={me} onSignOut={onSignOut} />;
    default:
      return <RestaurantsPage tab="been" />;
  }
}

function FoodIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M7 3v8a2 2 0 0 0 2 2v8M5 3v5M9 3v5M17 3c-2 2-2 6 0 9v9" />
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

function UsIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="9" cy="8" r="3.5" />
      <circle cx="17" cy="9" r="2.5" />
      <path d="M3 20c0-3.5 2.7-6 6-6s6 2.5 6 6M15 14.5c3 0 6 1.8 6 5.5" />
    </svg>
  );
}

/** In the Android app: a newer build is out (updates.ts). */
function UpdateBanner() {
  const { update, install, later } = useUpdate();
  if (!update) return null;
  return (
    <div className="notice row" role="status">
      <span className="grow">
        {update.name ? t('samfran {version} is available.', { version: update.name }) : t('A new version of samfran is available.')}
      </span>
      <button type="button" className="link quiet" onClick={later}>
        {t('Later')}
      </button>
      <button type="button" onClick={install}>
        {t('Update')}
      </button>
    </div>
  );
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
