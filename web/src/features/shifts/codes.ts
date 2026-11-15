// The shift codes, as internal/shift/codes.go reads them, so the edit
// screen can show what a code means while it's typed. The server's reading
// is the one that counts.
import type { ShiftDay, ShiftKind } from '../../api';
import { t } from '../../i18n';
import { locale } from '../../language';

const work = /^([0-9]{1,2})([A-Z])$/;

/** Full-width letters and digits as ASCII, upper case, no spaces (as the server does). */
export function normalize(code: string): string {
  return code
    .trim()
    .replace(/[０-９Ａ-Ｚａ-ｚ]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))
    .replace(/[\s　]/g, '')
    .toUpperCase();
}

/** What a code means: kind, label, and hours for a shift. */
export function explain(code: string, lengths: Record<string, number>): { kind: ShiftKind; label: string; hours?: [number, number]; problem?: string } {
  code = normalize(code);
  const m = code.match(work);
  if (m) {
    const start = Number(m[1]);
    const len = lengths[m[2]];
    const label = start < 9 ? 'Early' : start < 12 ? 'Middle' : start < 18 ? 'Late' : 'Night';
    if (start > 23) return { kind: 'work', label, problem: 'no such start hour' };
    if (!len) return { kind: 'work', label, problem: `unknown length for ${m[2]}` };
    return { kind: 'work', label, hours: [start, start + len] };
  }
  if (['普', '法', '休', '公', '公休'].includes(code)) return { kind: 'off', label: 'Day off' };
  if (['有休', '有', '有給'].includes(code)) return { kind: 'leave', label: 'Paid leave' };
  if (['トレ', '研修'].includes(code)) return { kind: 'training', label: 'Training' };
  return { kind: 'other', label: code };
}

// The workplace's own words, in every language of the app: they're what's
// on the sheet and what Sam says (the calendar feed uses them too).
const labels: Record<string, string> = {
  Early: '早番',
  Middle: '中番',
  Late: '遅番',
  Night: '夜勤',
  'Day off': '休み',
  'Paid leave': '有休',
  Training: '研修',
};

/** A day's label as the workplace says it (the server writes English). */
export function labelOf(label: string): string {
  return labels[label] ?? label;
}

/** "13:00", or "6:00 (+1)" for the next morning. */
export function clock(hour: number): string {
  const h = hour % 24;
  const whole = Math.floor(h);
  const min = Math.round((h - whole) * 60);
  return `${whole}:${String(min).padStart(2, '0')}${hour >= 24 ? ' (+1)' : ''}`;
}

/** A saved shift's times, e.g. "22:00–6:00", in the time zone of the shift. */
export function timesOf(d: ShiftDay): string {
  if (!d.start || !d.end) return '';
  // The server sends times with their offset (e.g. +09:00): show them as written.
  const hm = (iso: string) => {
    const m = iso.match(/T(\d\d):(\d\d)/);
    return m ? `${Number(m[1])}:${m[2]}` : '';
  };
  return `${hm(d.start)}–${hm(d.end)}`;
}

/** "Wed 4" for a YYYY-MM-DD. */
export function dayName(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(locale(), { weekday: 'short', day: 'numeric' });
}

/** "November 2026" for a YYYY-MM. */
export function monthName(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString(locale(), { month: 'long', year: 'numeric' });
}

/** The month before or after a YYYY-MM. */
export function addMonths(month: string, n: number): string {
  const [y, m] = month.split('-').map(Number);
  const d = new Date(y, m - 1 + n, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/** Every date of a YYYY-MM. */
export function datesOf(month: string): string[] {
  const [y, m] = month.split('-').map(Number);
  const n = new Date(y, m, 0).getDate();
  return Array.from({ length: n }, (_, i) => `${month}-${String(i + 1).padStart(2, '0')}`);
}

/**
 * Shrinks a photo before it's sent (phones take 10+ MB pictures): longest
 * side 2400 px, JPEG. Plenty to read a printed sheet.
 */
export async function shrinkPhoto(file: File): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 2400 / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  return new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error(t('Could not open that photo.')))), 'image/jpeg', 0.85),
  );
}
