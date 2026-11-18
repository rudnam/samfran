// The places on a Google map. The map is its own page (map.html, with
// Google's Content-Security-Policy; internal/server/mappage.go) in a frame
// sandboxed away from the app: this sends it the places with postMessage,
// and it says which pin was tapped. Tapping one shows that place's card
// under the map.
import { useEffect, useRef, useState } from 'react';
import type { Restaurant } from '../../api';
import { t } from '../../i18n';
import { language } from '../../language';
import { href } from '../../router';
import type { FromMap, ToMap } from '../../map/main';
import { categoryOf, CategoryTile } from './categories';
import { distanceText, type Spot } from './nearby';

export function PlacesMap({ places, mapKey, me }: { places: Restaurant[]; mapKey: string; me: Spot | null }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const [selected, setSelected] = useState<number | null>(null);
  const placed = places.filter((p) => p.lat != null && p.lng != null);
  const picked = placed.find((p) => p.id === selected) ?? null;

  useEffect(() => {
    function onMessage(e: MessageEvent) {
      if (e.source !== frame.current?.contentWindow) return; // only our map
      const m = e.data as FromMap;
      if (m?.type === 'ready') setReady(true);
      else if (m?.type === 'failed') setFailed(true);
      else if (m?.type === 'select') setSelected(m.id);
    }
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, []);

  useEffect(() => {
    if (!ready) return;
    const m: ToMap = {
      type: 'places',
      key: mapKey,
      lang: language(),
      places: placed.map((p) => ({ id: p.id, name: p.name, lat: p.lat!, lng: p.lng!, category: categoryOf(p.category) })),
      me,
      selected,
    };
    // The sandboxed page has no origin to name.
    frame.current?.contentWindow?.postMessage(m, '*');
  }, [ready, mapKey, me, selected, placed.map((p) => `${p.id}:${p.lat},${p.lng}:${p.category}`).join('|')]); // eslint-disable-line react-hooks/exhaustive-deps

  const missing = places.length - placed.length;
  return (
    <div className="stack tight">
      <div className="places-map">
        {failed ? (
          <p className="muted places-map-note">{t('The map isn’t available right now.')}</p>
        ) : (
          <iframe ref={frame} src="/map.html" sandbox="allow-scripts" title={t('Map of the places')} />
        )}
      </div>
      {picked && (
        <a className="spot map-card" href={href('restaurant', { id: picked.id })}>
          <CategoryTile category={categoryOf(picked.category)} />
          <span className="spot-body">
            <span className="spot-name">{picked.name}</span>
            {(picked.cuisine || picked.area) && <span className="spot-meta">{[picked.cuisine, picked.area].filter(Boolean).join(' · ')}</span>}
          </span>
          {me && <span className="spot-when"><strong>{distanceText(me, picked)}</strong></span>}
        </a>
      )}
      {missing > 0 && <p className="hint">{t('{n} not on the map yet: their place is still being looked up, or wasn’t found.', { n: missing })}</p>}
    </div>
  );
}
