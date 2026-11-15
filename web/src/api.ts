// Typed client for the Go API. Shapes mirror the Go handlers' JSON.
import { serverText } from './i18n';
import { language } from './language';
import { appHeaders, saveSession, server } from './native';

export interface Me {
  email: string;
  /** False for accounts that only sign in with Google. */
  has_password: boolean;
  /** The server emailed a link to confirm the address, and it hasn't been opened yet. */
  confirm_email: boolean;
  /** A throwaway demo account full of sample data, deleted within a day. */
  demo?: boolean;
  /** A new session's token, given to the Android app only (see native.ts). */
  token?: string;
}

/** The ways this server offers to sign in. */
export interface AuthOptions {
  google: boolean;
  /** Visitors can try a demo without an account. */
  demo?: boolean;
  /** A forgotten password can be reset by email. */
  reset?: boolean;
}

/** Someone in the space you share. */
export interface Member {
  name: string;
  email: string;
  /** The signed-in person. */
  me: boolean;
}

export interface Space {
  /** In the order they joined. */
  members: Member[];
  /** Addresses invited to join, who haven't yet. */
  invites: { email: string; created_at: string }[];
}

export interface Rating {
  name: string;
  me: boolean;
  /** 1 to 5. */
  stars: number;
}

export interface Visit {
  id: number;
  /** YYYY-MM-DD */
  visited_on: string;
  note: string;
  added_by: string;
}

export interface Restaurant {
  id: number;
  name: string;
  area: string;
  cuisine: string;
  map_url: string;
  notes: string;
  /** Who added it; empty if they've left. */
  added_by: string;
  created_at: string;
  visit_count: number;
  /** YYYY-MM-DD, or null if you haven't been. */
  last_visit: string | null;
  /** The signed-in person's first. */
  ratings: Rating[];
  /** Newest first; only from restaurant(id). */
  visits?: Visit[];
}

/** What a person types in about a restaurant. */
export interface RestaurantFields {
  name: string;
  area: string;
  cuisine: string;
  map_url: string;
  notes: string;
}

/** An error response from the API; message is safe to show the user. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(server + path, {
    method,
    // The server words emails (and anything else it writes) in the language
    // the app shows.
    headers: {
      'Accept-Language': language(),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...appHeaders(),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return undefined as T;
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(data?.error ?? res.statusText, res.status);
  return data as T;
}

/** In the Android app, keeps the token a sign-in returned. */
async function signedIn(p: Promise<Me>): Promise<Me> {
  const m = await p;
  if (m.token) await saveSession(m.token);
  return m;
}

export const api = {
  me: () => request<Me>('GET', '/api/me'),
  signUp: (email: string, password: string) => signedIn(request<Me>('POST', '/api/signup', { email, password })),
  signIn: (email: string, password: string) => signedIn(request<Me>('POST', '/api/login', { email, password })),
  /** In the Android app: trades the code from Google sign-in in the browser, and its secret, for a session. */
  appToken: (code: string, verifier: string) => signedIn(request<Me>('POST', '/api/auth/app-token', { code, verifier })),
  signOut: async () => {
    await request<void>('POST', '/api/logout');
    await saveSession('');
  },
  authOptions: () => request<AuthOptions>('GET', '/api/auth/options'),
  /** Signs in to a new demo account with sample data, in the app's language and this time zone. */
  startDemo: () => signedIn(request<Me>('POST', '/api/demo', { tz: Intl.DateTimeFormat().resolvedOptions().timeZone })),
  resendConfirmation: () => request<void>('POST', '/api/verify-email'),
  /** Emails a link to choose a new password, if an account uses this address. */
  forgotPassword: (email: string) => request<void>('POST', '/api/password/forgot', { email }),
  /** Sets a new password with the emailed link's token, and signs in. */
  resetPassword: (token: string, password: string) => signedIn(request<Me>('POST', '/api/password/reset', { token, password })),
  downloads: () => request<Downloads>('GET', '/api/downloads'),

  space: () => request<Space>('GET', '/api/space'),
  setMyName: (name: string) => request<Space>('PUT', '/api/space/me', { name }),
  invite: (email: string) => request<Space>('POST', '/api/space/invites', { email }),
  uninvite: (email: string) => request<Space>('DELETE', `/api/space/invites/${encodeURIComponent(email)}`),

  restaurants: () => request<Restaurant[]>('GET', '/api/restaurants'),
  restaurant: (id: number) => request<Restaurant>('GET', `/api/restaurants/${id}`),
  /** With visit, logs a first visit; with stars, the creator's rating. */
  createRestaurant: (f: RestaurantFields & { visit?: { visited_on: string; note?: string }; stars?: number }) =>
    request<Restaurant>('POST', '/api/restaurants', f),
  updateRestaurant: (id: number, f: Partial<RestaurantFields>) => request<Restaurant>('PATCH', `/api/restaurants/${id}`, f),
  deleteRestaurant: (id: number) => request<void>('DELETE', `/api/restaurants/${id}`),
  addVisit: (id: number, visited_on: string, note: string) =>
    request<Restaurant>('POST', `/api/restaurants/${id}/visits`, { visited_on, note }),
  deleteVisit: (id: number, visit: number) => request<Restaurant>('DELETE', `/api/restaurants/${id}/visits/${visit}`),
  /** The place's name from a Google Maps link, '' if it can't be read. */
  lookupMap: (url: string) => request<{ name: string; map_url: string }>('POST', '/api/restaurants/lookup', { url }),
  shifts: (month?: string) => request<ShiftMonth>('GET', month ? `/api/shifts?month=${month}` : '/api/shifts'),
  saveShifts: (month: string, days: { date: string; code: string }[]) =>
    request<ShiftMonth>('PUT', '/api/shifts/month', { month, days }),
  shiftSettings: () => request<ShiftSettings>('GET', '/api/shifts/settings'),
  saveShiftSettings: (s: Partial<Pick<ShiftSettings, 'person_name' | 'name_on_sheet' | 'lengths' | 'time_zone'>>) =>
    request<ShiftSettings>('PUT', '/api/shifts/settings', s),
  shiftFeed: (on: boolean) => request<ShiftSettings>(on ? 'POST' : 'DELETE', '/api/shifts/feed'),
  /** Reads a photo of the sheet; nothing is saved until saveShifts. */
  readShiftPhoto: async (photo: Blob) => {
    const form = new FormData();
    form.append('photo', photo, 'sheet.jpg');
    const res = await fetch(server + '/api/shifts/read', {
      method: 'POST',
      headers: { 'Accept-Language': language(), ...appHeaders() },
      body: form,
    });
    const data = await res.json().catch(() => null);
    if (!res.ok) throw new ApiError(data?.error ?? res.statusText, res.status);
    return data as ShiftMonth;
  },
  /** 0 clears the rating. */
  rate: (id: number, stars: number) => request<Restaurant>('PUT', `/api/restaurants/${id}/rating`, { stars }),
};

export type ShiftKind = 'work' | 'off' | 'leave' | 'training' | 'other';

export interface ShiftDay {
  /** YYYY-MM-DD */
  date: string;
  /** As on the sheet: "13F", "普", "有休"… */
  code: string;
  kind: ShiftKind;
  /** "Early", "Late", "Night", "Day off"… (English; the app translates). */
  label: string;
  /** ISO times, for a working day whose length is known. */
  start?: string;
  end?: string;
  /** Why a working day has no times, e.g. "unknown length for Z". */
  problem?: string;
}

export interface ShiftSettings {
  person_name: string;
  name_on_sheet: string;
  /** Hours on site for each letter of a shift code. */
  lengths: Record<string, number>;
  time_zone: string;
  /** The calendar feed's path on this site, or "" while it's off. */
  feed_path: string;
  /** Photos can be read on this server. */
  reader: boolean;
}

export interface ShiftMonth {
  /** YYYY-MM */
  month: string;
  settings: ShiftSettings;
  days: ShiftDay[];
  /** From a photo: the name on the row that was read, and the reader's doubts. */
  name_as_read?: string;
  notes?: string;
}

/** What the server offers to download: the Android app's APK, or null. */
export interface Downloads {
  android: { url: string; size: number; updated: string; version?: number; version_name?: string } | null;
}

/** A message to show for any thrown value, in the language in use. */
export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return serverText(err.message);
  return err instanceof Error ? err.message : String(err);
}
