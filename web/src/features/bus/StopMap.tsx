// A map of the stop: Google Maps itself, embedded, zoomed in close enough to
// see the shops and buildings around it, with a pin on the stop. It can be
// dragged and zoomed like Google Maps. The embed needs no API key; it's
// allowed in the page's Content-Security-Policy (frame-src,
// internal/httpx/middleware.go).
import { t, useLanguage } from '../../i18n';

/** small: a short strip, at the top of a stop's page. */
export function StopMap({ lat, lng, name, small }: { lat: number; lng: number; name: string; small?: boolean }) {
  const lang = useLanguage();
  const q = new URLSearchParams({ q: `${lat},${lng}`, z: '17', hl: lang, output: 'embed' });
  return (
    <div className={small ? 'stop-map small' : 'stop-map'}>
      <iframe
        src={`https://www.google.com/maps?${q}`}
        title={t('{stop} on the map', { stop: name || t('your stop') })}
        loading="lazy"
        referrerPolicy="no-referrer-when-downgrade"
        allowFullScreen
      />
    </div>
  );
}
