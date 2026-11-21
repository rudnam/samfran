// Nearby: where the person is, and how far each place is from there.
import { useState } from 'react';
import type { Restaurant } from '../../api';

export interface Spot {
  lat: number;
  lng: number;
}

/** Meters between two points (internal/geo's formula). */
export function meters(a: Spot, b: Spot): number {
  const r = 6371000;
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(h));
}

/** How far a place is: "350 m", "1.2 km", "14 km"; empty when it has no position. */
export function distanceText(me: Spot, p: Pick<Restaurant, 'lat' | 'lng'>): string {
  if (p.lat == null || p.lng == null) return '';
  return metersText(meters(me, { lat: p.lat, lng: p.lng }));
}

/** A distance as the list shows it: "350 m", "1.2 km", "14 km". */
export function metersText(m: number): string {
  if (m < 1000) return `${Math.max(10, Math.round(m / 10) * 10)} m`;
  if (m < 10000) return `${(m / 1000).toFixed(1)} km`;
  return `${Math.round(m / 1000)} km`;
}

/** Nearest first; places without a position last, newest first among them. */
export function byDistance(me: Spot, list: Restaurant[]): Restaurant[] {
  const d = (p: Restaurant) => (p.lat == null || p.lng == null ? Infinity : meters(me, { lat: p.lat, lng: p.lng }));
  return [...list].sort((a, b) => d(a) - d(b) || b.id - a.id);
}

export type LocationState = { status: 'idle' } | { status: 'asking' } | { status: 'here'; spot: Spot } | { status: 'failed'; denied: boolean };

/** The phone's location, asked for when Nearby is chosen. */
export function useLocation() {
  const [state, setState] = useState<LocationState>({ status: 'idle' });
  function ask() {
    if (!navigator.geolocation) {
      setState({ status: 'failed', denied: false });
      return;
    }
    setState((s) => (s.status === 'here' ? s : { status: 'asking' }));
    navigator.geolocation.getCurrentPosition(
      (pos) => setState({ status: 'here', spot: { lat: pos.coords.latitude, lng: pos.coords.longitude } }),
      (err) => setState({ status: 'failed', denied: err.code === err.PERMISSION_DENIED }),
      { enableHighAccuracy: false, timeout: 15000, maximumAge: 5 * 60 * 1000 },
    );
  }
  return { state, ask };
}

/**
 * Where the phone is, only if this site may already know: it never asks.
 * null when it may not, or the position doesn't come within a few seconds.
 */
export async function whereIfAllowed(): Promise<Spot | null> {
  try {
    if (!navigator.geolocation || !navigator.permissions) return null;
    if ((await navigator.permissions.query({ name: 'geolocation' })).state !== 'granted') return null;
  } catch {
    return null;
  }
  return new Promise((resolve) =>
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      () => resolve(null),
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 5 * 60 * 1000 },
    ),
  );
}
