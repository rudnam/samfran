// "‹ Our places" above a page: the way back, named after where it goes.
import { t, type Key } from '../i18n';
import { Icon } from './Icon';

/** [text], when given, names the place instead: a stop's own name, say. */
export function BackLink({ href, label, text }: { href: string; label: Key; text?: string }) {
  return (
    <a className="back" href={href}>
      <Icon name="back" size={18} />
      {text ?? t(label)}
    </a>
  );
}
