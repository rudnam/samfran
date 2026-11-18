import type { Restaurant } from '../../api';
import { locale } from '../../language';

/** Today in the device's time zone, as YYYY-MM-DD. */
export function today(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** "Nov 12", with the year when it isn't this year. */
export function shortDate(ymd: string): string {
  const [y, m, d] = ymd.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  const sameYear = y === new Date().getFullYear();
  return date.toLocaleDateString(locale(), { month: 'short', day: 'numeric', ...(sameYear ? {} : { year: 'numeric' }) });
}

/** The saved map link, else a Google Maps search for the name and area. */
export function mapLink(r: Restaurant): string {
  if (r.map_url) return r.map_url;
  const q = [r.name, r.area].filter(Boolean).join(' ');
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(q)}`;
}
