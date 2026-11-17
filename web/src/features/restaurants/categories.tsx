// A place's category: why you'd go there (internal/restaurant/category.go).
// Each has a line icon, drawn like the tab icons on a 24-unit grid with one
// round 2-unit stroke; the Android app draws the same paths
// (android/core/designsystem/…/icon/Icons.kt).
import { t } from '../../i18n';

export const categories = ['eat', 'nature', 'culture', 'fun', 'relax', 'shopping', 'other'] as const;
export type Category = (typeof categories)[number];

/** Icon paths, one per category, plus the Places tab's own. */
export const iconPaths: Record<Category | 'all' | 'places', string> = {
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
};

export function Icon({ name, size = 24 }: { name: keyof typeof iconPaths; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={iconPaths[name]} />
    </svg>
  );
}

/** The category's name, in full: "Nature & parks". */
export function categoryName(c: Category): string {
  switch (c) {
    case 'eat':
      return t('Eat & drink');
    case 'nature':
      return t('Nature & parks');
    case 'culture':
      return t('History & culture');
    case 'fun':
      return t('Fun & activities');
    case 'relax':
      return t('Onsen & relaxing');
    case 'shopping':
      return t('Shopping');
    case 'other':
      return t('Other');
  }
}

/** The category's short name, for a filter chip: "Nature". */
export function categoryShort(c: Category): string {
  switch (c) {
    case 'eat':
      return t('Eat');
    case 'nature':
      return t('Nature');
    case 'culture':
      return t('Culture');
    case 'fun':
      return t('Fun');
    case 'relax':
      return t('Relax');
    case 'shopping':
      return t('Shopping');
    case 'other':
      return t('Other');
  }
}

/** What goes in the category, for the form. */
export function categoryHint(c: Category): string {
  switch (c) {
    case 'eat':
      return t('Restaurants, cafés, bars, bakeries, sweets');
    case 'nature':
      return t('Parks, gardens, beaches, mountains, hikes');
    case 'culture':
      return t('Shrines, temples, castles, museums, galleries');
    case 'fun':
      return t('Theme parks, aquariums, zoos, arcades, sports, shows');
    case 'relax':
      return t('Onsen, sentō, saunas, spas');
    case 'shopping':
      return t('Malls, markets, outlets, shops');
    case 'other':
      return t('Anything that doesn’t fit');
  }
}

/** What the Kind field suggests for the category. */
export function kindPlaceholder(c: Category): string {
  switch (c) {
    case 'eat':
      return t('e.g. Ramen');
    case 'nature':
      return t('e.g. Park');
    case 'culture':
      return t('e.g. Shrine');
    case 'fun':
      return t('e.g. Aquarium');
    case 'relax':
      return t('e.g. Onsen');
    case 'shopping':
      return t('e.g. Outlet');
    case 'other':
      return '';
  }
}

/** The category of a place as the server sent it; an unknown one (from a newer server) is other. */
export function categoryOf(c: string | undefined): Category {
  if (!c) return 'eat';
  return (categories as readonly string[]).includes(c) ? (c as Category) : 'other';
}

/** The category's icon on its colour, as the list and the search show it. */
export function CategoryTile({ category, size = 'md' }: { category: Category; size?: 'sm' | 'md' }) {
  return (
    <span className={`tile cat-${category}${size === 'sm' ? ' sm' : ''}`} aria-hidden="true">
      <Icon name={category} size={size === 'sm' ? 18 : 22} />
    </span>
  );
}
