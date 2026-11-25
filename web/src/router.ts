// A tiny hash router: #/records?category=3. Hash routes need no server
// support and survive reloads.
import { useEffect, useState } from 'react';

export interface Route {
  page: string;
  params: URLSearchParams;
}

function parse(): Route {
  const [path, qs = ''] = location.hash.replace(/^#\/?/, '').split('?');
  return { page: path || 'home', params: new URLSearchParams(qs) };
}

/**
 * A page with changes that aren't saved: before the address moves on (a
 * link, a tab, Back), dirty() is asked, and if true, ask() whether to go.
 */
export interface LeaveGuard {
  dirty: () => boolean;
  ask: () => Promise<boolean>;
}

const listeners = new Set<() => void>();
// The address of the page showing, and where its entry is in the history:
// each entry is stamped with its place, so a move can be told apart (a new
// page, or Back or Forward by how many) and undone.
let shown = location.hash;
let pos = 0;
let guard: LeaveGuard | null = null;
let asking = false;

function placeOf(state: unknown): number | undefined {
  const p = (state as { pos?: unknown } | null)?.pos;
  return typeof p === 'number' ? p : undefined;
}
history.replaceState({ ...(history.state ?? {}), pos }, '');

function show() {
  shown = location.hash;
  // A new entry (a link, location.hash) has no place yet: it's the one after.
  pos = placeOf(history.state) ?? pos + 1;
  history.replaceState({ ...(history.state ?? {}), pos }, '');
  listeners.forEach((fn) => fn());
}

// One listener, before any page's, so a guarded page stays until it's answered.
window.addEventListener('hashchange', () => {
  if (location.hash === shown) return; // an undone move landing back here
  if (!guard?.dirty()) {
    show();
    return;
  }
  // The address has moved already (a link or Back can't be stopped first):
  // step back to this page's entry while asking, and on again if they go.
  const to = location.hash;
  const at = placeOf(history.state);
  const steps = at === undefined ? 1 : at - pos;
  if (steps === 0) history.replaceState({ pos }, '', shown || location.pathname + location.search);
  else history.go(-steps);
  if (asking) return;
  asking = true;
  void guard.ask().then((yes) => {
    asking = false;
    if (!yes) return;
    guard = null;
    if (steps === 0) {
      history.replaceState({ pos }, '', to);
      show();
    } else {
      history.go(steps);
    }
  });
});

/** Guards the page until the returned function is called. */
export function setLeaveGuard(g: LeaveGuard): () => void {
  guard = g;
  return () => {
    if (guard === g) guard = null;
  };
}

export function useRoute(): Route {
  const [route, setRoute] = useState(parse);
  useEffect(() => {
    const on = () => {
      setRoute(parse());
      window.scrollTo(0, 0);
    };
    listeners.add(on);
    return () => {
      listeners.delete(on);
    };
  }, []);
  return route;
}

export function href(page: string, params?: Record<string, string | number>): string {
  const qs = params ? new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString() : '';
  return `#/${page}${qs ? `?${qs}` : ''}`;
}

export function navigate(page: string, params?: Record<string, string | number>) {
  location.hash = href(page, params);
}

/** navigate, replacing the current history entry, e.g. to drop a used link's token. */
export function replaceRoute(page: string, params?: Record<string, string | number>) {
  history.replaceState({ pos }, '', href(page, params));
  window.dispatchEvent(new HashChangeEvent('hashchange'));
}
