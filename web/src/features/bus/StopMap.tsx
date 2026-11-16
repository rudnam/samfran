// A map of the stop: Google Maps itself, embedded, zoomed in close enough to
// see the shops and buildings around it, with a pin on the stop. It can be
// dragged and zoomed like Google Maps. The embed needs no API key; it's
// allowed in the page's Content-Security-Policy (frame-src,
// internal/httpx/middleware.go).
import { t, useLanguage } from '../../i18n';

export function StopMap({ lat, lng, name }: { lat: number; lng: number; name: string }) {
  const lang = useLanguage();
  const q = new URLSearchParams({ q: `${lat},${lng}`, z: '17', hl: lang, output: 'embed' });
  return (
    <div className="stop-map">
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
