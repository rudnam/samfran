// Adding a place from Google Maps. Maps shares text like
//   "Hanabi Grill\nhttps://maps.app.goo.gl/AbC123"
// (sometimes with the address on a line of its own, sometimes only the
// link). An installed web app gets it as ?title=&text=&url=
// (manifest.webmanifest's share_target), and the Add form reads a pasted link
// the same way. The Android app reads it with its own copy of this
// (core/model's Shared).

export interface Shared {
  name: string;
  /** The first web link in the text, or ''. */
  url: string;
}

const link = /https?:\/\/[^\s<>"]+/i;

/** The place's name and link in text shared from Maps (or anywhere). */
export function parseShared(...parts: (string | null | undefined)[]): Shared {
  const text = parts.filter(Boolean).join('\n');
  const url = text.match(link)?.[0] ?? '';
  const name =
    text
      .split('\n')
      .map((line) => line.replace(link, '').trim())
      // Not a leftover like "Check out this place:" or a bare address.
      .find((line) => line.length > 0 && !/^(check out|see|shared)\b.*:$/i.test(line)) ?? '';
  // Maps sometimes writes "Name · rating · type": keep the name.
  return { name: name.split(' · ')[0].trim().slice(0, 200), url };
}

/** True for a Google Maps address the server can read a name from. */
export function isMapsLink(url: string): boolean {
  try {
    const u = new URL(url);
    const host = u.hostname.toLowerCase();
    return (
      host === 'maps.app.goo.gl' ||
      (host === 'goo.gl' && u.pathname.startsWith('/maps')) ||
      /^(www\.|maps\.)?google\.(com|[a-z]{2}|com?\.[a-z]{2})$/.test(host)
    );
  } catch {
    return false;
  }
}

/**
 * An installed web app opened from Android's share menu gets
 * /app/?title=…&text=…&url=…. Read once, then dropped from the address.
 */
export function takeSharedFromUrl(): string | null {
  const params = new URLSearchParams(location.search);
  if (!params.has('text') && !params.has('url')) return null;
  const text = [params.get('title'), params.get('text'), params.get('url')].filter(Boolean).join('\n');
  for (const k of ['title', 'text', 'url']) params.delete(k);
  const qs = params.toString();
  history.replaceState(history.state, '', location.pathname + (qs ? `?${qs}` : '') + location.hash);
  return text || null;
}
