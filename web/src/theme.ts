// Light or dark: the one the person picked (kept in this browser), or else
// the device's. The stylesheet follows the device by itself; a pick sets
// data-theme on <html>, which overrides it.

export type Theme = 'light' | 'dark';

const storageKey = 'samfran:theme';

/** The theme the person picked, or null to follow the device. */
export function pickedTheme(): Theme | null {
  try {
    const v = localStorage.getItem(storageKey);
    return v === 'light' || v === 'dark' ? v : null;
  } catch {
    return null; // storage blocked: follow the device
  }
}

function apply(t: Theme | null) {
  if (t) document.documentElement.dataset.theme = t;
  else delete document.documentElement.dataset.theme;
}

/** Picks a theme (null: follow the device). */
export function setTheme(t: Theme | null) {
  try {
    if (t) localStorage.setItem(storageKey, t);
    else localStorage.removeItem(storageKey);
  } catch {
    // Not kept, but still used until the page is closed.
  }
  apply(t);
}

apply(pickedTheme());
