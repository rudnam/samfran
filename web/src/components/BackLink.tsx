// "‹ Our spots" above a page: the way back, named after where it goes.
import { t, type Key } from '../i18n';

export function BackLink({ href, label }: { href: string; label: Key }) {
  return (
    <a className="back" href={href}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M15 5l-7 7 7 7" />
      </svg>
      {t(label)}
    </a>
  );
}
