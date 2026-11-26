// Typed client for the Go API. Shapes mirror the Go handlers' JSON.
import { clearCache } from './cache';
import { serverText } from './i18n';
import { language } from './language';

export interface Me {
  email: string;
  /** False for accounts that only sign in with Google. */
  has_password: boolean;
  /** The server emailed a link to confirm the address, and it hasn't been opened yet. */
  confirm_email: boolean;
  /** A throwaway demo account full of sample data, deleted within a day. */
  demo?: boolean;
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
  /** The open invite link, if any (its address is shown only when made). */
  invite_link: { expires_at: string } | null;
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
  /** Oldest first; missing from servers before photos. */
  photos?: Photo[];
}

/** A photo from a visit: url is the original, exactly as it was taken; thumb_url a small copy. */
export interface Photo {
  id: number;
  url: string;
  thumb_url: string;
  /** The original's pixels; 0 when not known. */
  width: number;
  height: number;
  added_by: string;
}

/** Something one of you said about a place you've been to. */
export interface Comment {
  id: number;
  body: string;
  /** The writer's name. */
  by: string;
  /** Written by the signed-in person, who alone can delete it. */
  me: boolean;
  created_at: string;
}

/** A place to go: a restaurant, park, shrine… (it began as a list of restaurants). */
export interface Restaurant {
  id: number;
  name: string;
  area: string;
  /** The kind of place: "Ramen", "公園". */
  cuisine: string;
  /** Why you'd go: eat, nature, culture, fun, relax, shopping or other (features/restaurants/categories.tsx). */
  category: string;
  /** Where it is; null until known (the server looks it up from the map link or name). */
  lat?: number | null;
  lng?: number | null;
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
  /** The newest photo's thumbnail, shown instead of the category's icon; null without photos. */
  cover?: string | null;
  photo_count?: number;
  /** Newest first; only from restaurant(id). */
  visits?: Visit[];
  /** Oldest first; only from restaurant(id), and left out when there are none. */
  comments?: Comment[];
  /** The visits with a note, newest first, for searching. */
  visit_notes?: { visited_on: string; note: string }[];
}

/** What a person types in about a place. */
export interface RestaurantFields {
  name: string;
  area: string;
  cuisine: string;
  category: string;
  map_url: string;
  notes: string;
  /** Where it is, both or neither: from the place picked in the search. */
  lat?: number;
  lng?: number;
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
  // A change makes what pages remember stale, whoever's signed in afterwards.
  if (method !== 'GET') clearCache();
  const res = await fetch(path, {
    method,
    // The server words emails (and anything else it writes) in the language
    // the app shows.
    headers: {
      'Accept-Language': language(),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  // Again, in case a page loaded the old state while the change was on its way.
  if (method !== 'GET') clearCache();
  if (res.status === 204) return undefined as T;
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(data?.error ?? res.statusText, res.status);
  return data as T;
}

/** How often a photo being read is asked after, and when to give up on it (the server gives up after 3 minutes). */
const readPollMs = 2000;
const readGiveUpMs = 5 * 60_000;

export const api = {
  me: () => request<Me>('GET', '/api/me'),
  signUp: (email: string, password: string) => request<Me>('POST', '/api/signup', { email, password }),
  signIn: (email: string, password: string) => request<Me>('POST', '/api/login', { email, password }),
  signOut: () => request<void>('POST', '/api/logout'),
  authOptions: () => request<AuthOptions>('GET', '/api/auth/options'),
  /** Signs in to a new demo account with sample data, in the app's language and this time zone. */
  startDemo: () => request<Me>('POST', '/api/demo', { tz: Intl.DateTimeFormat().resolvedOptions().timeZone }),
  resendConfirmation: () => request<void>('POST', '/api/verify-email'),
  /** Emails a link to choose a new password, if an account uses this address. */
  forgotPassword: (email: string) => request<void>('POST', '/api/password/forgot', { email }),
  /** Sets a new password with the emailed link's token, and signs in. */
  resetPassword: (token: string, password: string) => request<Me>('POST', '/api/password/reset', { token, password }),

  space: () => request<Space>('GET', '/api/space'),
  setMyName: (name: string) => request<Space>('PUT', '/api/space/me', { name }),
  invite: (email: string) => request<Space>('POST', '/api/space/invites', { email }),
  /** Makes an invite link (replacing an unused one): its path, e.g. /join/…, shown once. */
  createInviteLink: () => request<{ path: string; expires_at: string }>('POST', '/api/space/invite-link'),
  cancelInviteLink: () => request<Space>('DELETE', '/api/space/invite-link'),
  uninvite: (email: string) => request<Space>('DELETE', `/api/space/invites/${encodeURIComponent(email)}`),

  restaurants: () => request<Restaurant[]>('GET', '/api/restaurants'),
  restaurant: (id: number) => request<Restaurant>('GET', `/api/restaurants/${id}`),
  /** The Google Maps key for the places' map; empty for no map. */
  mapKey: () => request<{ key: string }>('GET', '/api/restaurants/map'),
  /** With visit, logs a first visit; with stars, the creator's rating. */
  createRestaurant: (f: RestaurantFields & { visit?: { visited_on: string; note?: string }; stars?: number }) =>
    request<Restaurant>('POST', '/api/restaurants', f),
  updateRestaurant: (id: number, f: Partial<RestaurantFields>) => request<Restaurant>('PATCH', `/api/restaurants/${id}`, f),
  deleteRestaurant: (id: number) => request<void>('DELETE', `/api/restaurants/${id}`),
  /** When you went (YYYY-MM-DD): the one date a place shows. */
  setBeen: (id: number, visited_on: string) => request<Restaurant>('PUT', `/api/restaurants/${id}/been`, { visited_on }),
  /** Not been yet: back on the want-to-go list. */
  clearBeen: (id: number) => request<Restaurant>('DELETE', `/api/restaurants/${id}/been`),
  /** Another time you went. */
  addVisit: (id: number, visited_on: string, note = '') => request<Restaurant>('POST', `/api/restaurants/${id}/visits`, { visited_on, note }),
  updateVisit: (id: number, visit: number, v: { visited_on?: string; note?: string }) =>
    request<Restaurant>('PATCH', `/api/restaurants/${id}/visits/${visit}`, v),
  deleteVisit: (id: number, visit: number) => request<Restaurant>('DELETE', `/api/restaurants/${id}/visits/${visit}`),
  /**
   * Adds a photo to a visit: the original file as it was taken (never a
   * smaller copy), with a thumbnail made here; onProgress hears how much
   * has gone (0 to 1).
   */
  addPhoto: (id: number, visit: number, photo: Blob, thumb: Blob, size: { width: number; height: number }, onProgress?: (sent: number) => void) => {
    clearCache();
    const form = new FormData();
    form.append('width', String(size.width));
    form.append('height', String(size.height));
    form.append('thumb', thumb, 'thumb.jpg');
    form.append('photo', photo, photo instanceof File ? photo.name : 'photo.jpg');
    // XMLHttpRequest, not fetch: only it says how much of the photo has gone.
    return new Promise<Restaurant>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/api/restaurants/${id}/visits/${visit}/photos`);
      xhr.setRequestHeader('Accept-Language', language());
      xhr.responseType = 'json';
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress?.(e.loaded / e.total);
      xhr.onload = () => {
        clearCache();
        if (xhr.status >= 200 && xhr.status < 300) resolve(xhr.response);
        else reject(new ApiError(xhr.response?.error ?? xhr.statusText, xhr.status));
      };
      xhr.onerror = () => reject(new TypeError('Failed to fetch'));
      xhr.send(form);
    });
  },
  deletePhoto: (id: number, photo: number) => request<Restaurant>('DELETE', `/api/restaurants/${id}/photos/${photo}`),
  /** The place's name from a Google Maps link, '' if it can't be read. */
  /** position: also find lat/lng by the place's name when the link has none (found_by_name). */
  lookupMap: (url: string, position = false) =>
    request<{ name: string; map_url: string; lat?: number; lng?: number; found_by_name?: boolean }>('POST', '/api/restaurants/lookup', { url, position }),
  /** Places to eat whose names match q (2+ characters), from OpenStreetMap; near ones first when near is given. */
  searchPlaces: (q: string, near?: { lat: number; lng: number }) =>
    request<FoundPlace[]>('GET', `/api/restaurants/search?${new URLSearchParams({ q, ...(near ? { lat: String(near.lat), lng: String(near.lng) } : {}) })}`),
  /**
   * Places to eat whose names match q (2+ characters): from Google Places when
   * the server has a key (each has a place_id; ask placeDetails for the rest),
   * else as searchPlaces. session is the form's, shared with placeDetails.
   */
  suggestPlaces: (q: string, session: string, near?: { lat: number; lng: number }) =>
    request<FoundPlace[]>(
      'GET',
      `/api/restaurants/suggest?${new URLSearchParams({ q, session, lang: language(), ...(near ? { lat: String(near.lat), lng: String(near.lng) } : {}) })}`,
    ),
  /** A place Google suggested: where it is, its Maps link, and its food. */
  placeDetails: (placeId: string, session: string) =>
    request<FoundPlace>('GET', `/api/restaurants/place/${encodeURIComponent(placeId)}?${new URLSearchParams({ session, lang: language() })}`),
  shifts: (month?: string) => request<ShiftMonth>('GET', month ? `/api/shifts?month=${month}` : '/api/shifts'),
  saveShifts: (
    month: string,
    days: { date: string; code: string; people: ShiftCoworker[] }[],
    corrections?: { from: string; to: string }[],
    /** This month's 締め申請通知 days (dates in it); left out, they stay as they are. */
    closing_days?: number[],
  ) => request<ShiftMonth>('PUT', '/api/shifts/month', { month, days, corrections, closing_days }),
  shiftSettings: () => request<ShiftSettings>('GET', '/api/shifts/settings'),
  saveShiftSettings: (s: Partial<Pick<ShiftSettings, 'person_name' | 'name_on_sheet' | 'lengths' | 'time_zone' | 'closing_days'>>) =>
    request<ShiftSettings>('PUT', '/api/shifts/settings', s),
  shiftFeed: (on: boolean) => request<ShiftSettings>(on ? 'POST' : 'DELETE', '/api/shifts/feed'),
  /**
   * Reads a photo of the sheet; nothing is saved until saveShifts. Reading
   * takes about a minute, longer than one request may last, so this starts
   * the read and asks after it until it's done, telling onProgress how far
   * it's got: the photo going up, then the days as they're read.
   */
  readShiftPhoto: async (photo: Blob, onProgress?: (p: ReadProgress) => void) => {
    const form = new FormData();
    form.append('photo', photo, 'sheet.jpg');
    onProgress?.({ stage: 'sending', sent: 0 });
    // XMLHttpRequest, not fetch: only it says how much of the photo has gone.
    const started = await new Promise<{ id: string }>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/shifts/reads');
      xhr.setRequestHeader('Accept-Language', language());
      xhr.responseType = 'json';
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress?.({ stage: 'sending', sent: e.loaded / e.total });
      xhr.onload = () =>
        xhr.status >= 200 && xhr.status < 300 ? resolve(xhr.response) : reject(new ApiError(xhr.response?.error ?? xhr.statusText, xhr.status));
      xhr.onerror = () => reject(new TypeError('Failed to fetch'));
      xhr.send(form);
    });
    onProgress?.({ stage: 'reading', days_read: 0, days_in_month: 0 });
    const until = Date.now() + readGiveUpMs;
    let failures = 0;
    while (Date.now() < until) {
      await new Promise((resolve) => setTimeout(resolve, readPollMs));
      let res: Response;
      try {
        res = await fetch(`/api/shifts/reads/${started.id}`, {
          headers: { 'Accept-Language': language() },
        });
      } catch (err) {
        // A phone moving between networks: try again a few times.
        if (++failures > 3) throw err;
        continue;
      }
      if (res.status === 202) {
        const p = await res.json().catch(() => null);
        if (p) onProgress?.({ stage: 'reading', name_as_read: p.name_as_read, days_read: p.days_read ?? 0, days_in_month: p.days_in_month ?? 0 });
        continue;
      }
      const data = await res.json().catch(() => null);
      if (!res.ok) throw new ApiError(data?.error ?? res.statusText, res.status);
      return data as ShiftMonth;
    }
    throw new ApiError("the photo couldn't be read just now; try again, or enter the shifts by hand", 504);
  },
  mcpTokens: () => request<MCPToken[]>('GET', '/api/mcp/tokens'),
  deleteMCPToken: (id: number) => request<void>('DELETE', `/api/mcp/tokens/${id}`),
  mcpClient: (clientId: string, redirectUri: string) =>
    request<MCPClientInfo>('GET', `/api/mcp/authorize?${new URLSearchParams({ client_id: clientId, redirect_uri: redirectUri })}`),
  /** Allows or denies a connection; answers where to send the browser. */
  mcpAuthorize: (r: { client_id: string; redirect_uri: string; code_challenge: string; state: string; allow: boolean }) =>
    request<{ redirect_to: string }>('POST', '/api/mcp/authorize', r),
  busStop: () => request<BusStop>('GET', '/api/bus/stop'),
  /** Stops and stations whose names match q (2+ characters); near ones first when near is given. */
  searchStops: (q: string, near?: { lat: number; lng: number }) =>
    request<FoundStop[]>('GET', `/api/bus/search?${new URLSearchParams({ q, ...(near ? { lat: String(near.lat), lng: String(near.lng) } : {}) })}`),
  busStops: () => request<BusStops>('GET', '/api/bus/stops'),
  addBusStop: (s: BusStopInput) => request<BusStops>('POST', '/api/bus/stops', s),
  changeBusStop: (id: number, s: BusStopInput) => request<BusStops>('PUT', `/api/bus/stops/${id}`, s),
  deleteBusStop: (id: number) => request<BusStops>('DELETE', `/api/bus/stops/${id}`),
  activity: () => request<ActivityEntry[]>('GET', '/api/activity'),
  /** 0 clears the rating. */
  rate: (id: number, stars: number) => request<Restaurant>('PUT', `/api/restaurants/${id}/rating`, { stars }),
  addComment: (id: number, body: string) => request<Restaurant>('POST', `/api/restaurants/${id}/comments`, { body }),
  deleteComment: (id: number, comment: number) => request<Restaurant>('DELETE', `/api/restaurants/${id}/comments/${comment}`),
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
  /** Others on the same shift (same code that day), names as on the sheet. */
  with?: string[];
  /** Others on other shifts whose hours overlap this one, by when they start. */
  others?: ShiftThere[];
  /** Everyone kept around the day, there during it or not: with and others are worked out from them. */
  people?: ShiftCoworker[];
}

/** Someone else at work around a day, as read off the sheet. */
export interface ShiftCoworker {
  name: string;
  code: string;
  /** -1: a night shift from the day before; 1: an early shift the next day. */
  day_offset?: number;
}

/** Someone else there during a shift, and when (ISO, with offset). */
export interface ShiftThere extends ShiftCoworker {
  start?: string;
  end?: string;
}

/** How far reading a photo has got. */
export type ReadProgress =
  | { stage: 'sending'; sent: number }
  | { stage: 'reading'; name_as_read?: string; days_read: number; days_in_month: number };

export interface ShiftSettings {
  person_name: string;
  name_on_sheet: string;
  /** Hours on site for each letter of a shift code. */
  lengths: Record<string, number>;
  time_zone: string;
  /** 締め申請通知: days of the month the hours are sent in; 31 means the last day. */
  closing_days: number[];
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
  /** 締め申請通知 this month, as dates in it; custom: set for this month only. (Not on a photo read.) */
  closing_days?: number[];
  closing_custom?: boolean;
  /** From a photo: the name on the row that was read, and the reader's doubts. */
  name_as_read?: string;
  notes?: string;
}

/** A connection from Claude (or another MCP client). */
export interface MCPToken {
  id: number;
  name: string;
  created_at: string;
  last_used_at: string | null;
  /** True for a connection made by signing in from the app. */
  signed_in?: boolean;
}

/** An MCP client asking to connect, for the Connect page. */
export interface MCPClientInfo {
  client_name: string;
  /** Where the browser goes back to after the person answers. */
  redirect_host: string;
}

/** A place found by name, for the add-a-place form. */
export interface FoundPlace {
  name: string;
  /** City and district. */
  area: string;
  /** Street or neighbourhood, to tell branches apart. */
  street: string;
  /** OpenStreetMap's (restaurant, cafe, park, museum…) or Google's (ramen_restaurant, park…). */
  kind: string;
  /** The category the kind of place is in. */
  category?: string;
  lat: number;
  lng: number;
  /** Google's, for a place Google suggested: until placeDetails, only name and street (an address line) are set. */
  place_id?: string;
  /** From placeDetails: the kind of place, for the Kind field, when it says one. */
  food?: string;
  /** From placeDetails: the place's own Google Maps link. */
  map_url?: string;
  /** How far it is from where the search was asked from, when it was given one. */
  meters?: number;
  /** Who found it: google or openstreetmap (missing from an older server: openstreetmap). */
  source?: string;
}

/** Where the signed-in person gets off the bus. */
export interface FoundStop {
  name: string;
  /** City, district, street. */
  area: string;
  kind: StopKind;
  lat: number;
  lng: number;
}

export interface BusStop {
  /** False until a stop is chosen. */
  set: boolean;
  name: string;
  lat: number;
  lng: number;
  /** Ring this many metres before the stop. */
  alert_meters: number;
  /** How the phone buzzes at the stop. */
  buzz: Buzz;
}

/** The kinds of shift a stop can be for, as the server labels shifts. */
export type ShiftLabel = 'Early' | 'Middle' | 'Late' | 'Night';

/** A bus stop, or a train station. */
export type StopKind = 'bus' | 'train';

/** One of the signed-in person's stops. */
export interface SavedStop {
  id: number;
  name: string;
  lat: number;
  lng: number;
  /** Ring this many metres before the stop. */
  alert_meters: number;
  kind: StopKind;
  /** How the phone buzzes at this stop. */
  buzz: Buzz;
  /** Seconds of buzzing before the phone's alarm sound joins in here, if it isn't stopped: 0 (never), 30, 60 or 120. */
  sound_after: number;
  /** The kinds of shift it's the stop for; each is on one stop at most. */
  shifts: ShiftLabel[];
  /** The Android app asks "Start bus alarm?" when a shift whose stop this is ends. */
  ask: boolean;
}

export interface BusStops {
  /** Oldest first. */
  stops: SavedStop[];
  /** The stop used last: the one to go to when no shift says which. 0 when there are none. */
  last_id: number;
  /** How older apps buzz, at every stop (each stop has its own now). */
  buzz: Buzz;
}

export interface BusStopInput {
  name: string;
  lat: number;
  lng: number;
  alert_meters: number;
  /** Taken from the person's other stops. Left out, a changed stop keeps its own. */
  shifts?: ShiftLabel[];
  /** Left out, a changed stop keeps its own; a new one asks. */
  ask?: boolean;
  /** Left out, a changed stop keeps its own; a new one is a bus stop. */
  kind?: StopKind;
  /** Left out, a changed stop keeps its own; a new one buzzes strong. */
  buzz?: Buzz;
  /** Left out, a changed stop keeps its own; a new one's is a minute. */
  sound_after?: number;
}

/** How the phone buzzes at the stop: gentle (as it always did), strong (full strength, longer buzzes), strongest (hardly a pause). */
export type Buzz = 'gentle' | 'strong' | 'strongest';

/** A change to what you share, for Recent changes. */
export interface ActivityEntry {
  id: number;
  at: string;
  /** The member's name; '' if they've left. */
  who: string;
  me: boolean;
  via: 'app' | 'claude';
  kind: string;
  details: Record<string, unknown>;
}

/** A message to show for any thrown value, in the language in use. */
export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return serverText(err.message);
  return err instanceof Error ? err.message : String(err);
}
