// Whether a place found by the search (or typed in) is one already saved.
// The name alone isn't enough: a chain has a branch in every town, and two
// towns can each have a shop with one name. So where each is decides it.
import type { Restaurant } from '../../api';
import { meters } from './nearby';

/**
 * Two places with one name this close are the same place: room for one map
 * putting it on the building and another on its entrance (search.go merges
 * one map's duplicates within 50 m).
 */
const samePlace = 150; // meters

/** Where a found or typed place is, as far as it's known. */
export interface Where {
  lat?: number | null;
  lng?: number | null;
  /** City and district, as the Area field has it. */
  area?: string;
  /** A Google suggestion's address line ("静岡県浜松市中央区砂山町"), before its details. */
  street?: string;
  map_url?: string;
}

/**
 * same: the place is this saved one. elsewhere: a saved place with the same
 * name somewhere else, or maybe the same one where that can't be told (a
 * Google suggestion before its details, a place typed in with no area).
 */
export interface SavedMatch {
  same?: Restaurant;
  elsewhere?: Restaurant;
}

const sameName = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();

/** The town in an Area: its first word ("浜松市 中央区" → "浜松市"). */
function town(area: string): string {
  return area.trim().split(/[\s,、]+/)[0].toLowerCase();
}

const known = (lat?: number | null, lng?: number | null) => lat != null && lng != null && (lat !== 0 || lng !== 0);

/** same, other, or unknown: whether r is the place at w. */
function compare(r: Restaurant, w: Where): 'same' | 'other' | 'unknown' {
  if (w.map_url && r.map_url && w.map_url === r.map_url) return 'same';
  if (known(r.lat, r.lng) && known(w.lat, w.lng)) {
    return meters({ lat: r.lat!, lng: r.lng! }, { lat: w.lat!, lng: w.lng! }) < samePlace ? 'same' : 'other';
  }
  const saved = town(r.area);
  if (!saved) return 'unknown';
  if (w.area?.trim()) return town(w.area) === saved ? 'unknown' : 'other';
  if (w.street?.trim()) return w.street.toLowerCase().includes(saved) ? 'unknown' : 'other';
  return 'unknown';
}

/**
 * The saved place that name at w is, if it surely is one, else a saved
 * place with that name elsewhere. Typed in by hand with nothing to go on
 * (sure is false), a saved place with the name counts as the same, as it
 * always has.
 */
export function findSaved(saved: Restaurant[], name: string, w: Where, sure: boolean): SavedMatch {
  if (!name.trim()) return {};
  const named = saved.filter((r) => sameName(r.name, name));
  let unknown: Restaurant | undefined;
  let other: Restaurant | undefined;
  for (const r of named) {
    const c = compare(r, w);
    if (c === 'same') return { same: r };
    if (c === 'unknown') unknown ??= r;
    else other ??= r;
  }
  if (unknown && !sure) return { same: unknown };
  const elsewhere = unknown ?? other;
  return elsewhere ? { elsewhere } : {};
}
