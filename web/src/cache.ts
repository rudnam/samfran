// What each page last loaded, kept while the app is open, so coming back to
// a page shows it at once and then catches up with the server, instead of
// "Loading…" every time. Any change sent to the server empties it (api.ts),
// so nothing stale outlives the change that made it stale.
import { useEffect, useState } from 'react';

const store = new Map<string, unknown>();

/** Forgets everything: after any change, and when someone signs in or out. */
export function clearCache() {
  store.clear();
}

/**
 * What load answers for key: the last answer straight away if there is one,
 * then the fresh one. set replaces it, e.g. with the answer to a change.
 */
export function useCached<T>(key: string, load: () => Promise<T>, errorText: (err: unknown) => string) {
  const [state, setState] = useState<{ key: string; data: T | null; error: string }>(() => ({
    key,
    data: (store.get(key) as T | undefined) ?? null,
    error: '',
  }));

  useEffect(() => {
    let alive = true;
    setState({ key, data: (store.get(key) as T | undefined) ?? null, error: '' });
    load().then(
      (data) => {
        store.set(key, data);
        if (alive) setState({ key, data, error: '' });
      },
      (err) => {
        if (alive) setState((s) => ({ ...s, error: errorText(err) }));
      },
    );
    return () => {
      alive = false;
    };
  }, [key]); // eslint-disable-line react-hooks/exhaustive-deps

  // Between a key changing and the effect above running, state is the old key's.
  const current = state.key === key ? state : { data: (store.get(key) as T | undefined) ?? null, error: '' };
  return {
    data: current.data,
    error: current.error,
    set: (data: T) => {
      store.set(key, data);
      setState({ key, data, error: '' });
    },
  };
}
