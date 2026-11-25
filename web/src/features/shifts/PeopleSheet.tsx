// Who's at work around one day, while a month is checked or fixed: everyone
// read off the sheet (or added), each with the code in their cell, in one list.
// A read lists only those there during the day's shift; anyone who isn't
// after a code is changed is shown in grey, and isn't kept when the month is
// saved. Names and codes can be fixed.
import { useEffect, useRef } from 'react';
import { t } from '../../i18n';
import { clock, dayName, hoursAround, normalize, overlaps } from './codes';
import { Icon } from '../../components/Icon';

/** One person in the editor. read is the name as the photo reader gave it, to learn from a fix. */
export interface PersonRow {
  key: number;
  name: string;
  code: string;
  day_offset: number;
  read?: string;
}

let nextKey = 1;
export function personRow(p: { name: string; code: string; day_offset?: number }, fromPhoto: boolean): PersonRow {
  return { key: nextKey++, name: p.name, code: p.code, day_offset: p.day_offset ?? 0, read: fromPhoto ? p.name : undefined };
}

/** Whether someone is there during a shift of hours (a shift without hours: on its exact code). */
export function isThere(p: PersonRow, code: string, hours: [number, number] | undefined, lengths: Record<string, number>): boolean {
  if (!p.name.trim()) return false;
  if (!hours) return p.day_offset === 0 && normalize(p.code) === normalize(code);
  const theirs = hoursAround(p.code, p.day_offset, lengths);
  return !!theirs && overlaps(theirs, hours);
}

export function PeopleSheet({
  date,
  code,
  hours,
  rows,
  shownKeys,
  lengths,
  onChange,
  onAdd,
  onClose,
  names,
}: {
  date: string;
  code: string;
  hours?: [number, number];
  rows: PersonRow[];
  /** Those listed first, as decided when the sheet opened, so nobody jumps away while being edited. */
  shownKeys: Set<number>;
  lengths: Record<string, number>;
  onChange: (rows: PersonRow[]) => void;
  /** Adds an empty row, on the day's own code, among those listed first. */
  onAdd: () => void;
  onClose: () => void;
  /** Names to suggest while typing: the month's other people. */
  names: string[];
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  // Those there first, as when the sheet opened, so nobody jumps away while being edited.
  const ordered = [...rows.filter((r) => shownKeys.has(r.key)), ...rows.filter((r) => !shownKeys.has(r.key))];
  const update = (key: number, change: Partial<PersonRow>) => onChange(rows.map((r) => (r.key === key ? { ...r, ...change } : r)));
  const listId = `names-${date}`;

  function row(r: PersonRow, i: number) {
    const theirs = hoursAround(r.code, r.day_offset, lengths);
    const there = isThere(r, code, hours, lengths);
    const label = r.name.trim() || t('Person {n}', { n: i + 1 });
    return (
      <li key={r.key} className={there || !r.name.trim() ? '' : 'away'}>
        <input
          className="person-name"
          value={r.name}
          maxLength={40}
          list={listId}
          placeholder={t('Their name')}
          aria-label={t('Name of person {n}', { n: i + 1 })}
          autoFocus={!r.name && !r.read}
          onChange={(e) => update(r.key, { name: e.target.value })}
        />
        <input
          className="code-input small"
          value={r.code}
          maxLength={12}
          list="shift-codes"
          aria-label={t('Code of {name}', { name: label })}
          onChange={(e) => update(r.key, { code: e.target.value })}
        />
        {/* Their hours (which day their cell is on comes from the read). */}
        <span className="person-when small">{theirs ? `${clock(theirs[0])}–${clock(theirs[1])}` : '–'}</span>
        <button type="button" className="link quiet" aria-label={t('Remove {name}', { name: label })} onClick={() => onChange(rows.filter((x) => x.key !== r.key))}>
          <Icon name="close" size={18} />
        </button>
      </li>
    );
  }

  return (
    <dialog
      ref={dialog}
      className="ask people-sheet"
      aria-labelledby="people-title"
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === dialog.current) onClose();
      }}
    >
      <div className="stack">
        <div className="stack tight">
          <h2 id="people-title">{t('Who’s there on {day}', { day: dayName(date) })}</h2>
          <span className="muted small">
            {normalize(code)}
            {hours && ` · ${clock(hours[0])}–${clock(hours[1])}`} · {t('Names and codes as on the sheet.')}
          </span>
        </div>
        <datalist id={listId}>
          {names.map((n) => (
            <option key={n} value={n} />
          ))}
        </datalist>
        {ordered.length > 0 ? (
          <ul className="people-edit" aria-label={t('Everyone that day')}>
            {ordered.map((r) => row(r, rows.indexOf(r)))}
          </ul>
        ) : (
          <p className="muted small">{t('Nobody yet.')}</p>
        )}
        <button type="button" className="secondary" onClick={onAdd}>
          <Icon name="add" size={18} />
          {t('Add someone')}
        </button>
        <div className="row end">
          <button type="button" onClick={onClose}>
            {t('Done')}
          </button>
        </div>
      </div>
    </dialog>
  );
}
