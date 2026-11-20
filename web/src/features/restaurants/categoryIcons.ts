// The categories and their line icons, with nothing else imported, so the
// map page (src/map/main.ts) can draw them without the app.
export const categories = ['eat', 'nature', 'culture', 'fun', 'relax', 'shopping', 'other'] as const;
export type Category = (typeof categories)[number];

/** Icon paths, one per category, plus the Places tab's own. */
export const iconPaths: Record<Category | 'all' | 'places' | 'nearby' | 'list' | 'map' | 'search' | 'down' | 'sort' | 'check', string> = {
  eat: 'M7 3v8a2 2 0 0 0 2 2v8M5 3v5M9 3v5M17 3c-2 2-2 6 0 9v9',
  nature: 'M12 3a5 5 0 0 1 5 5 4 4 0 0 1-1 7.5H8A4 4 0 0 1 7 8a5 5 0 0 1 5-5zM12 15.5V21M12 12.5l-2.5-2M9 21h6',
  culture: 'M3 5c4 1.2 14 1.2 18 0M5 9.5h14M7 6.3V21M17 6.3V21M12 6.5v3',
  fun: 'M3 8a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4zM14.5 6v2M14.5 11v2M14.5 16v2',
  relax:
    'M8.5 3c-1 1.3 1 2.4 0 3.7s1 2.4 0 3.7M12 2.5c-1 1.3 1 2.4 0 3.7s1 2.4 0 3.7M15.5 3c-1 1.3 1 2.4 0 3.7s1 2.4 0 3.7M5 12.5c-.8 1-1 2-1 3 0 3 3.6 5.5 8 5.5s8-2.5 8-5.5c0-1-.2-2-1-3',
  shopping: 'M5.5 8h13l-1 13h-11zM9 10V6.5a3 3 0 0 1 6 0V10',
  other: 'M6 12h.01M12 12h.01M18 12h.01',
  all: 'M4 6h16M4 12h16M4 18h16',
  places: 'M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21zM14.5 9.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z',
  nearby: 'M19 12a7 7 0 1 1-14 0a7 7 0 1 1 14 0zM12 2v3M12 19v3M2 12h3M19 12h3',
  list: 'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01',
  map: 'M9 4 3 6v14l6-2 6 2 6-2V4l-6 2zM9 4v14M15 6v14',
  search: 'M16.5 10.5a6 6 0 1 1-12 0a6 6 0 1 1 12 0zM15 15l5 5',
  down: 'M6 9l6 6 6-6',
  sort: 'M7 4v16M3 8l4-4 4 4M17 20V4M13 16l4 4 4-4',
  check: 'M5 12.5l4.5 4.5L19 7',
};


/** Each category's colour on the map: its tile's ink (styles.css --tile-*-ink). */
export const pinColors: Record<Category, string> = {
  eat: '#85490f',
  nature: '#23653a',
  culture: '#a8291a',
  fun: '#2f4aa0',
  relax: '#7a2f8f',
  shopping: '#835500',
  other: '#7a6c5f',
};
