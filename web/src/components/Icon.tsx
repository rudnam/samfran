// A line icon from the shared set (features/restaurants/categoryIcons.ts),
// drawn like the tab icons on a 24-unit grid with one round 2-unit stroke; the
// Android app draws the same paths (android/core/designsystem/…/icon/Icons.kt).
import { iconPaths } from '../features/restaurants/categoryIcons';

export function Icon({ name, size = 24 }: { name: keyof typeof iconPaths; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={iconPaths[name]} />
    </svg>
  );
}
