// Changes the other person made that haven't been looked at on this device:
// a dot on the Us tab, and "new" beside them in Recent changes. What's been
// seen is the id of the newest change shown, kept in the browser.
import { useEffect, useSyncExternalStore } from 'react';
import { api, type ActivityEntry } from '../../api';

const key = 'activity-seen';
let unseen = 0;
let checked = 0;
const listeners = new Set<() => void>();

function seenId(): number | null {
  try {
    const v = localStorage.getItem(key);
    return v === null ? null : Number(v) || 0;
  } catch {
    return null;
  }
}

function setUnseen(n: number) {
  if (n === unseen) return;
  unseen = n;
  listeners.forEach((fn) => fn());
}

/** The id up to which changes have been seen here; null on a device that has seen none. */
export function seenUpTo(): number | null {
  return seenId();
}

/** Recent changes has shown the list: nothing in it is new any more. */
export function markSeen(list: ActivityEntry[]) {
  const max = list.reduce((m, e) => Math.max(m, e.id), 0);
  try {
    localStorage.setItem(key, String(max));
  } catch {
    // Private browsing: the dot comes back next time.
  }
  setUnseen(0);
}

function count(list: ActivityEntry[]) {
  const seen = seenId();
  // A device's first look: everything so far counts as seen.
  if (seen === null) {
    try {
      localStorage.setItem(key, String(list.reduce((m, e) => Math.max(m, e.id), 0)));
    } catch {
      // Private browsing.
    }
    return;
  }
  setUnseen(list.filter((e) => !e.me && e.id > seen).length);
}

function check() {
  if (Date.now() - checked < 60 * 1000) return;
  checked = Date.now();
  api.activity().then(count, () => {});
}

/** How many of the other person's changes are new here. Looks when the app opens and comes back into view. */
export function useUnseen(): number {
  useEffect(() => {
    check();
    const on = () => {
      if (document.visibilityState === 'visible') check();
    };
    document.addEventListener('visibilitychange', on);
    return () => document.removeEventListener('visibilitychange', on);
  }, []);
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    () => unseen,
  );
}
