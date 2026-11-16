// Who else is on a shift: "With タナカ ユイ, スズキ ケン". Each name stays in
// one piece when the line wraps.
import { Fragment } from 'react';
import { tx } from '../../i18n';
import { locale } from '../../language';

export function WithNames({ names, className = 'with' }: { names?: string[]; className?: string }) {
  if (!names?.length) return null;
  const sep = locale().startsWith('ja') ? '、' : ', ';
  const list = names.map((n, i) => (
    <Fragment key={n}>
      {i > 0 && sep}
      <span className="nowrap">{n}</span>
    </Fragment>
  ));
  return <span className={className}>{tx('With {names}', { names: list })}</span>;
}
