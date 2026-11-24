// The places on a Google map, in a sandboxed frame (map.html). The app
// (features/restaurants/PlacesMap.tsx) sends the places with postMessage;
// this page loads Google Maps with the key it's given, puts a pin with the
// category's icon on each place, and says which pin was tapped. It sees
// nothing else of the app.
//
// The pins are drawn on an OverlayView rather than with Google's markers,
// so they look like the app and need no Map ID.
import { iconPaths, pinColors, type Category } from '../features/restaurants/categoryIcons';

export interface MapPlace {
  id: number;
  name: string;
  lat: number;
  lng: number;
  category: Category;
}

/** What the app sends. */
export interface ToMap {
  type: 'places';
  key: string;
  lang: string;
  places: MapPlace[];
  /** Where the person is, when they asked for Nearby. */
  me: { lat: number; lng: number } | null;
  selected: number | null;
}

/** What this page says back. */
export type FromMap = { type: 'ready' } | { type: 'select'; id: number | null } | { type: 'failed' };

/* eslint-disable @typescript-eslint/no-explicit-any -- Google's script has no types here */
declare global {
  interface Window {
    google?: any;
    samfranMapLoaded?: () => void;
    gm_authFailure?: () => void;
  }
}

const tell = (m: FromMap) => window.parent.postMessage(m, '*');

let latest: ToMap | null = null;
let map: any = null;
let overlays: any[] = [];
let shownIds = '';
let loading = false;

window.addEventListener('message', (e) => {
  if (e.source !== window.parent || !e.data || e.data.type !== 'places') return;
  latest = e.data as ToMap;
  if (map) draw();
  else load(latest);
});

function load(m: ToMap) {
  if (loading) return;
  loading = true;
  window.samfranMapLoaded = start;
  window.gm_authFailure = () => tell({ type: 'failed' }); // a wrong or restricted key
  const s = document.createElement('script');
  const q = new URLSearchParams({ key: m.key, language: m.lang, region: 'JP', callback: 'samfranMapLoaded', loading: 'async' });
  s.src = `https://maps.googleapis.com/maps/api/js?${q}`;
  s.async = true;
  s.onerror = () => tell({ type: 'failed' });
  document.head.append(s);
}

function start() {
  const g = window.google.maps;
  map = new g.Map(document.getElementById('map'), {
    center: { lat: 34.7108, lng: 137.7261 },
    zoom: 13,
    disableDefaultUI: true,
    zoomControl: true,
    fullscreenControl: false,
    clickableIcons: false,
    gestureHandling: 'greedy',
    backgroundColor: '#ece6dc', // warm, like the page, until the map is drawn
  });
  map.addListener('click', () => tell({ type: 'select', id: null }));
  draw();
}

/** A pin or the person's dot, at a position on the map. */
function overlay(lat: number, lng: number, el: HTMLElement) {
  const g = window.google.maps;
  const o = new g.OverlayView();
  o.onAdd = () => o.getPanes().overlayMouseTarget.append(el);
  o.draw = () => {
    const p = o.getProjection().fromLatLngToDivPixel(new g.LatLng(lat, lng));
    if (p) {
      el.style.left = `${p.x}px`;
      el.style.top = `${p.y}px`;
    }
  };
  o.onRemove = () => el.remove();
  o.setMap(map);
  return o;
}

function pin(p: MapPlace, on: boolean): HTMLElement {
  const b = document.createElement('button');
  b.className = on ? 'pin on' : 'pin';
  b.style.background = pinColors[p.category] ?? pinColors.other;
  b.setAttribute('aria-label', p.name);
  b.setAttribute('aria-pressed', String(on));
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', '#fff');
  svg.setAttribute('stroke-width', '2.2');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', iconPaths[p.category] ?? iconPaths.other);
  svg.append(path);
  b.append(svg);
  // Not to the map: a tap on a pin isn't a tap on the map.
  b.addEventListener('click', (e) => {
    e.stopPropagation();
    tell({ type: 'select', id: p.id });
  });
  return b;
}

function draw() {
  const m = latest;
  if (!m || !map) return;
  const g = window.google.maps;
  for (const o of overlays) o.setMap(null);
  overlays = m.places.map((p) => overlay(p.lat, p.lng, pin(p, p.id === m.selected)));
  if (m.me) {
    const dot = document.createElement('div');
    dot.className = 'me';
    overlays.push(overlay(m.me.lat, m.me.lng, dot));
  }
  // Fit the map to the places when they change, not when a pin is picked.
  const ids = m.places.map((p) => p.id).join(',') + (m.me ? `@${m.me.lat},${m.me.lng}` : '');
  if (ids === shownIds) return;
  shownIds = ids;
  const points = [...m.places, ...(m.me ? [m.me] : [])];
  if (points.length === 1) {
    map.setCenter(points[0]);
    map.setZoom(15);
  } else if (points.length > 1) {
    const bounds = new g.LatLngBounds();
    for (const p of points) bounds.extend(p);
    map.fitBounds(bounds, 48);
  }
}

tell({ type: 'ready' });
