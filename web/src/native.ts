// The Android app (android/, Capacitor) runs this same web app from files
// inside the app, at https://localhost. There the API is another origin, so
// requests go to the server named at build time (VITE_SERVER, see
// .env.android) and sign in with a bearer token instead of a cookie (see
// internal/account/app.go). On the web, none of this applies. As in RudWallet
// and Rudnote.
import { App as CapApp } from '@capacitor/app';
import { Browser } from '@capacitor/browser';
import { Capacitor, registerPlugin } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';

/** True inside the Android app. */
export const isApp = Capacitor.isNativePlatform();

/** Where the API is: '' (this site) on the web, the server in the app. */
export const server: string = isApp ? (import.meta.env.VITE_SERVER ?? '') : '';

// Kept in Capacitor's preferences.
const sessionKey = 'session';
let token = '';

/** Reads the saved session. Call once before the first request. */
export async function loadSession(): Promise<void> {
  if (!isApp) return;
  token = (await Preferences.get({ key: sessionKey })).value ?? '';
}

/** Headers that sign the app's requests in. */
export function appHeaders(): Record<string, string> {
  if (!isApp) return {};
  const h: Record<string, string> = { 'X-App-Client': 'android' };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

/** Keeps the token from a sign-in (or forgets it, with ''). */
export async function saveSession(t: string): Promise<void> {
  if (!isApp || t === token) return;
  token = t;
  if (t) await Preferences.set({ key: sessionKey, value: t });
  else await Preferences.remove({ key: sessionKey });
}

// Google sign-in in the app: Google doesn't allow it inside a web view, so
// the app opens the phone's browser, like OAuth for native apps (RFC 8252,
// RFC 7636). It keeps a random secret and sends its hash; the server sends
// the browser back to <package>://auth?code=…, and the app trades the code
// and the secret for a session token (internal/account/app.go).
const verifierKey = 'google-verifier';

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Opens Google sign-in in the phone's browser; onAuthLinks gets the result. */
export async function startGoogleSignIn(): Promise<void> {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
  await Preferences.set({ key: verifierKey, value: verifier });
  await Browser.open({ url: `${server}/api/auth/google?app_challenge=${challenge}` });
}

/** Splits <package>://host/path?query by hand: older web views don't parse a custom scheme's host. */
function parseLink(raw: string): { host: string; path: string; params: URLSearchParams } {
  const [where, query = ''] = raw.replace(/^[^:]+:\/\//, '').split('?');
  const [host = '', ...path] = where.split('/');
  return { host, path: path.join('/'), params: new URLSearchParams(query) };
}

let authLaunchHandled = false;

/**
 * <package>://auth?code=… from Google sign-in in the phone's browser: gives
 * onAuth the code and the secret to trade for a session, or why it failed.
 */
export function onAuthLinks(onAuth: (result: { code: string; verifier: string } | { error: string }) => void): () => void {
  if (!isApp) return () => {};
  const handle = async (raw: string) => {
    const { host, params } = parseLink(raw);
    if (host !== 'auth') return;
    Browser.close().catch(() => {}); // already closed on some phones
    const code = params.get('code');
    const verifier = (await Preferences.get({ key: verifierKey })).value ?? '';
    await Preferences.remove({ key: verifierKey });
    onAuth(code && verifier ? { code, verifier } : { error: params.get('signin_error') ?? 'failed' });
  };
  const listener = CapApp.addListener('appUrlOpen', (e) => void handle(e.url));
  if (!authLaunchHandled) {
    authLaunchHandled = true;
    CapApp.getLaunchUrl().then((l) => {
      if (l?.url) void handle(l.url);
    });
  }
  return () => {
    listener.then((l) => l.remove());
  };
}

let openLaunchHandled = false;

/**
 * <package>://bus?start=1 and <package>://us, which the app's own
 * notifications open (android/…/ShiftPrompt.java, ActivityJob.java): calls
 * onOpen with the page and its parameters.
 */
export function onOpenLinks(onOpen: (page: string, params: URLSearchParams) => void): () => void {
  if (!isApp) return () => {};
  const handle = (raw: string) => {
    const { host, params } = parseLink(raw);
    if (host === 'bus' || host === 'us') onOpen(host, params);
  };
  const listener = CapApp.addListener('appUrlOpen', (e) => handle(e.url));
  if (!openLaunchHandled) {
    openLaunchHandled = true;
    CapApp.getLaunchUrl().then((l) => {
      if (l?.url) handle(l.url);
    });
  }
  return () => {
    listener.then((l) => l.remove());
  };
}

// The bus-stop alarm (android/…/BusAlarmPlugin.java and BusTripService.java).
export interface TripStatus {
  running: boolean;
  /** Metres to the stop, -1 until the phone knows. */
  distance: number;
  /** Buzzing now, until stopped. */
  buzzing: boolean;
  /** Notifications are allowed (needed for the alarm and the trip). */
  notifications: boolean;
}

export const busAlarm = registerPlugin<{
  start(o: { lat: number; lng: number; radius: number; name: string }): Promise<TripStatus>;
  stop(): Promise<TripStatus>;
  stopBuzz(): Promise<TripStatus>;
  status(): Promise<TripStatus>;
  test(o: { name: string }): Promise<TripStatus>;
  here(): Promise<{ lat: number; lng: number; accuracy: number }>;
}>('BusAlarm');

// Text shared to the app from another one, e.g. a place from Google Maps
// (android/…/ShareTargetPlugin.java).
const shareTarget = registerPlugin<{
  take(): Promise<{ text?: string | null }>;
  addListener(event: 'shared', fn: (data: { text: string }) => void): Promise<{ remove: () => void }>;
}>('ShareTarget');

/** Calls onShare with text shared to the app: what started it, and anything shared while it's open. */
export function onSharedText(onShare: (text: string) => void): () => void {
  if (!isApp) return () => {};
  shareTarget.take().then((r) => {
    if (r.text) onShare(r.text);
  }, () => {});
  const listener = shareTarget.addListener('shared', (d) => onShare(d.text));
  return () => {
    listener.then((l) => l.remove());
  };
}
