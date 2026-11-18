// The introduction page at /. Links meant for the app (/#/...) and the app
// installed to a home screen are sent on to /app/.
import './styles.css';
import './landing.css';
import { language, setLanguage } from './language';

const forApp = location.hash.startsWith('#/') || matchMedia('(display-mode: standalone)').matches;
if (forApp) location.replace('/app/' + location.search + location.hash);
else {
  translate();
  document.querySelector('#language-toggle')?.addEventListener('click', () => {
    setLanguage(language() === 'ja' ? 'en' : 'ja');
    translate();
  });
  offerDownload();
}

// "Download for Android" shows while the server offers the APK (ANDROID_APK).
function offerDownload() {
  fetch('/api/downloads')
    .then((r) => (r.ok ? r.json() : null))
    .then((d: { android?: { url: string } | null } | null) => {
      if (!d?.android) return;
      document.querySelectorAll<HTMLAnchorElement>('[data-download]').forEach((a) => {
        a.href = d.android!.url;
        a.hidden = false;
      });
    })
    .catch(() => {}); // without it, the page simply has no download button
}

// The page is written in English; each text's Japanese is in its data-ja
// (HTML, ours). The English is kept in data-en to switch back.
function translate() {
  const ja = language() === 'ja';
  document.querySelectorAll<HTMLElement>('[data-ja]').forEach((el) => {
    el.dataset.en ??= el.innerHTML;
    el.innerHTML = ja ? el.dataset.ja! : el.dataset.en;
  });
  document.documentElement.lang = ja ? 'ja' : 'en';
  const description = document.querySelector<HTMLMetaElement>('meta[data-ja-content]');
  if (description) {
    description.dataset.en ??= description.content;
    description.content = ja ? description.dataset.jaContent! : description.dataset.en;
  }
  // The switch names the other language, in that language.
  const toggle = document.querySelector<HTMLElement>('#language-toggle');
  if (toggle) toggle.lang = ja ? 'en' : 'ja';
}
