// Each time you went to a place, newest first: its date, a note, and its
// photos. Photos go up exactly as they were taken (never shrunk), with a
// small thumbnail made here for the strip; tapping one opens the original.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, errorMessage, type Photo, type Restaurant, type Visit } from '../../api';
import { confirmDialog } from '../../components/Overlay';
import { t, tn } from '../../i18n';
import { ErrorText } from '../../components/ErrorText';
import { Icon } from './categories';
import { shortDate, today } from './format';

const maxNote = 1000; // as the server's
const maxPhotoMB = 40; // as the server's (internal/restaurant/photo.go)

/** A photo on its way up: a preview here, and how much has gone. */
interface Sending {
  key: string;
  preview: string;
  sent: number;
}

/** A thumbnail of file for the strip (longest side 480 px), and the original's size in pixels. */
async function thumbnail(file: File): Promise<{ thumb: Blob; width: number; height: number }> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    throw new Error(t('That photo can’t be opened here. Choose a JPEG, PNG or WebP picture.'));
  }
  const { width, height } = bitmap;
  const scale = Math.min(1, 480 / Math.max(width, height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(width * scale));
  canvas.height = Math.max(1, Math.round(height * scale));
  canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const thumb = await new Promise<Blob>((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error(t('That photo can’t be opened here. Choose a JPEG, PNG or WebP picture.')))), 'image/jpeg', 0.82),
  );
  return { thumb, width, height };
}

export function Visits({ place, onChange }: { place: Restaurant; onChange: (r: Restaurant) => void }) {
  const visits = place.visits ?? [];
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // The new visit's date while "Went again" is open.
  const [adding, setAdding] = useState<string | null>(null);
  const [addNote, setAddNote] = useState('');
  // The photo open in the viewer: its visit's photos and which one.
  const [viewing, setViewing] = useState<{ visit: number; index: number } | null>(null);

  async function run(fn: () => Promise<Restaurant>) {
    setBusy(true);
    setError('');
    try {
      onChange(await fn());
      return true;
    } catch (err) {
      setError(errorMessage(err));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function addVisit(e: FormEvent) {
    e.preventDefault();
    if (adding && (await run(() => api.addVisit(place.id, adding, addNote)))) {
      setAdding(null);
      setAddNote('');
    }
  }

  const open = viewing && visits.find((v) => v.id === viewing.visit);
  return (
    <div className="card stack">
      <div className="row spread">
        <h2>{t('Visits')}</h2>
        {adding === null && (
          <button type="button" className="secondary small" disabled={busy} onClick={() => setAdding(today())}>
            <Icon name="add" size={18} />
            {t('Went again')}
          </button>
        )}
      </div>
      {adding !== null && (
        <form className="visit-form stack tight" onSubmit={addVisit}>
          <label className="field">
            {t('When did you go?')}
            <input type="date" required max={today()} value={adding} onChange={(e) => setAdding(e.target.value)} />
          </label>
          <label className="field">
            {t('Note (optional)')}
            <textarea rows={2} maxLength={maxNote} value={addNote} placeholder={t('What you had, how it was')} onChange={(e) => setAddNote(e.target.value)} />
          </label>
          <div className="row wrap">
            <button type="submit" disabled={busy}>
              {t('Save')}
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setAdding(null)}>
              {t('Cancel')}
            </button>
          </div>
        </form>
      )}
      <ErrorText message={error} />
      <ul className="visits" aria-label={t('Visits')}>
        {visits.map((v) => (
          <VisitItem
            key={v.id}
            place={place}
            visit={v}
            last={visits.length === 1}
            onChange={onChange}
            onOpen={(index) => setViewing({ visit: v.id, index })}
          />
        ))}
      </ul>
      {open && viewing && (open.photos ?? []).length > 0 && (
        <PhotoViewer
          photos={open.photos ?? []}
          start={Math.min(viewing.index, (open.photos ?? []).length - 1)}
          date={open.visited_on}
          onClose={() => setViewing(null)}
          onDelete={async (photo) => {
            const yes = await confirmDialog({ title: t('Delete this photo?'), body: t('It’s gone for both of you. This can’t be undone.'), confirm: t('Delete'), danger: true });
            if (!yes) return;
            const left = (open.photos ?? []).length - 1;
            if (await run(() => api.deletePhoto(place.id, photo.id))) {
              if (left === 0) setViewing(null);
            }
          }}
        />
      )}
    </div>
  );
}

/** One visit: its date and note (both editable), and its photos with a tile to add more. */
function VisitItem({
  place,
  visit,
  last,
  onChange,
  onOpen,
}: {
  place: Restaurant;
  visit: Visit;
  /** The only visit: deleting it puts the place back on Want to go. */
  last: boolean;
  onChange: (r: Restaurant) => void;
  onOpen: (index: number) => void;
}) {
  const photos = visit.photos ?? [];
  const [editing, setEditing] = useState(false);
  const [date, setDate] = useState(visit.visited_on);
  const [note, setNote] = useState(visit.note);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [sending, setSending] = useState<Sending[]>([]);
  const picker = useRef<HTMLInputElement>(null);

  async function run(fn: () => Promise<Restaurant>) {
    setBusy(true);
    setError('');
    try {
      onChange(await fn());
      return true;
    } catch (err) {
      setError(errorMessage(err));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    if (await run(() => api.updateVisit(place.id, visit.id, { visited_on: date, note }))) setEditing(false);
  }

  async function remove() {
    const yes = await confirmDialog({
      title: t('Delete the visit on {date}?', { date: shortDate(visit.visited_on) }),
      body: [
        photos.length > 0 ? tn(photos.length, 'Its photo goes with it.', 'Its {n} photos go with it.') : '',
        last ? t('{name} goes back to Want to go.', { name: place.name }) : '',
        t('This can’t be undone.'),
      ]
        .filter(Boolean)
        .join(' '),
      confirm: t('Delete'),
      danger: true,
    });
    if (yes && (await run(() => api.deleteVisit(place.id, visit.id)))) setEditing(false);
  }

  /** Sends the photos picked, one after another, as they are. */
  async function send(files: FileList | null) {
    const list = Array.from(files ?? []);
    if (picker.current) picker.current.value = '';
    if (list.length === 0) return;
    setError('');
    const queued = list.map((f, i) => ({ key: `${Date.now()}-${i}`, preview: URL.createObjectURL(f), sent: 0 }));
    setSending((s) => [...s, ...queued]);
    const problems: string[] = [];
    for (const [i, file] of list.entries()) {
      const key = queued[i].key;
      try {
        if (file.size > maxPhotoMB * 1024 * 1024) throw new Error(t('A photo must be at most {max} MB.', { max: maxPhotoMB }));
        const { thumb, width, height } = await thumbnail(file);
        const fresh = await api.addPhoto(place.id, visit.id, file, thumb, { width, height }, (sent) =>
          setSending((s) => s.map((x) => (x.key === key ? { ...x, sent } : x))),
        );
        onChange(fresh);
      } catch (err) {
        problems.push(errorMessage(err));
      } finally {
        URL.revokeObjectURL(queued[i].preview);
        setSending((s) => s.filter((x) => x.key !== key));
      }
    }
    if (problems.length) setError([...new Set(problems)].join(' '));
  }

  return (
    <li className="visit stack tight">
      {editing ? (
        <form className="visit-form stack tight" onSubmit={save}>
          <label className="field">
            {t('When did you go?')}
            <input type="date" required max={today()} value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <label className="field">
            {t('Note (optional)')}
            <textarea rows={2} maxLength={maxNote} value={note} placeholder={t('What you had, how it was')} onChange={(e) => setNote(e.target.value)} />
          </label>
          <div className="row wrap">
            <button type="submit" disabled={busy}>
              {t('Save')}
            </button>
            <button
              type="button"
              className="secondary"
              disabled={busy}
              onClick={() => {
                setEditing(false);
                setDate(visit.visited_on);
                setNote(visit.note);
              }}
            >
              {t('Cancel')}
            </button>
            <button type="button" className="link danger" disabled={busy} onClick={remove}>
              {t('Delete this visit')}
            </button>
          </div>
        </form>
      ) : (
        <>
          <p className="visit-head">
            <strong>{shortDate(visit.visited_on)}</strong>
            <button
              type="button"
              className="link quiet small"
              disabled={busy}
              onClick={() => {
                setDate(visit.visited_on);
                setNote(visit.note);
                setEditing(true);
              }}
              aria-label={t('Edit the visit on {date}', { date: shortDate(visit.visited_on) })}
            >
              {t('Edit')}
            </button>
          </p>
          {visit.note && <p className="prewrap">{visit.note}</p>}
        </>
      )}
      <div className="photo-strip" aria-label={t('Photos from {date}', { date: shortDate(visit.visited_on) })}>
        {photos.map((p, i) => (
          <button type="button" key={p.id} className="photo-thumb" onClick={() => onOpen(i)} aria-label={t('Photo {n} of {total}', { n: i + 1, total: photos.length })}>
            <img src={p.thumb_url} alt="" loading="lazy" decoding="async" />
          </button>
        ))}
        {sending.map((s) => (
          <span key={s.key} className="photo-thumb sending" role="progressbar" aria-label={t('Sending a photo')} aria-valuenow={Math.round(s.sent * 100)} aria-valuemin={0} aria-valuemax={100}>
            <img src={s.preview} alt="" />
            <span className="photo-progress" style={{ width: `${Math.round(s.sent * 100)}%` }} />
          </span>
        ))}
        <label className="photo-add">
          <Icon name="camera" size={22} />
          <span>{photos.length + sending.length ? t('Add') : t('Add photos')}</span>
          <input ref={picker} type="file" accept="image/*" multiple className="visually-hidden" onChange={(e) => send(e.target.files)} />
        </label>
      </div>
      <ErrorText message={error} />
    </li>
  );
}

/**
 * The photos of a visit, one at a time and at full size: the original as it
 * was taken, over its thumbnail until it's in. Arrows, a swipe or the keys
 * move between them; Escape or the back button closes it.
 */
function PhotoViewer({
  photos,
  start,
  date,
  onClose,
  onDelete,
}: {
  photos: Photo[];
  start: number;
  date: string;
  onClose: () => void;
  onDelete: (p: Photo) => void;
}) {
  const box = useRef<HTMLDialogElement>(null);
  const [index, setIndex] = useState(start);
  const i = Math.min(index, photos.length - 1);
  const photo = photos[i];
  const swipe = useRef<number | null>(null);

  useEffect(() => {
    const d = box.current;
    if (d && !d.open) d.showModal();
    return () => d?.close();
  }, []);

  const go = (step: number) => setIndex((n) => Math.max(0, Math.min(photos.length - 1, Math.min(n, photos.length - 1) + step)));

  // The arrow keys, wherever the focus is (an arrow button that's gone at the end leaves it nowhere).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') go(-1);
      if (e.key === 'ArrowRight') go(1);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // go only reads the photos' count through setIndex's updater.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [photos.length]);

  return (
    <dialog
      ref={box}
      className="photo-viewer"
      aria-label={t('Photos from {date}', { date: shortDate(date) })}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      <div className="viewer-bar">
        <span className="grow">
          {shortDate(date)} · {i + 1} / {photos.length}
          {photo.added_by && <span className="muted"> · {photo.added_by}</span>}
        </span>
        <a className="button secondary small" href={photo.url} target="_blank" rel="noreferrer" download>
          {t('Download')}
        </a>
        <button type="button" className="secondary small danger-text" onClick={() => onDelete(photo)}>
          {t('Delete')}
        </button>
        <button type="button" className="icon-button" onClick={onClose} aria-label={t('Close')}>
          <Icon name="close" size={22} />
        </button>
      </div>
      <div
        className="viewer-stage"
        onPointerDown={(e) => (swipe.current = e.clientX)}
        onPointerUp={(e) => {
          if (swipe.current === null) return;
          const dx = e.clientX - swipe.current;
          swipe.current = null;
          if (Math.abs(dx) > 50) go(dx < 0 ? 1 : -1);
        }}
        onClick={(e) => e.target === e.currentTarget && onClose()}
      >
        <img
          key={photo.id}
          src={photo.url}
          alt={t('Photo {n} of {total}', { n: i + 1, total: photos.length })}
          style={{ backgroundImage: `url("${photo.thumb_url}")` }}
          draggable={false}
        />
        {i > 0 && (
          <button type="button" className="viewer-nav prev" onClick={() => go(-1)} aria-label={t('Previous photo')}>
            <Icon name="chevronLeft" size={28} />
          </button>
        )}
        {i < photos.length - 1 && (
          <button type="button" className="viewer-nav next" onClick={() => go(1)} aria-label={t('Next photo')}>
            <Icon name="chevronRight" size={28} />
          </button>
        )}
      </div>
    </dialog>
  );
}
