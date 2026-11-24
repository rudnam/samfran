// A place's category: why you'd go there (internal/restaurant/category.go).
// Each has a line icon, drawn like the tab icons on a 24-unit grid with one
// round 2-unit stroke; the Android app draws the same paths
// (android/core/designsystem/…/icon/Icons.kt).
import { t } from '../../i18n';
import { categories, type Category } from './categoryIcons';

export { categories, iconPaths, type Category } from './categoryIcons';
import { Icon } from '../../components/Icon';
export { Icon };

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
