// Asking before something can't be undone, and offering to undo what can:
// a dialog in the app's own style (not the browser's confirm box), and a
// short message along the bottom with an Undo button.
import { useEffect, useRef, useSyncExternalStore } from 'react';
import { t } from '../i18n';

interface Ask {
  title: string;
  body?: string;
  /** The button that goes ahead, e.g. "Delete". */
  confirm: string;
  danger?: boolean;
  answer: (yes: boolean) => void;
}

interface Toast {
  id: number;
  message: string;
  /** e.g. "Undo". */
  action: string;
  /** The action was tapped. */
  onAction: () => void;
  /** It went away without the action: timed out, or made way for another. */
  onClose: () => void;
}

let asking: Ask | null = null;
let toast: Toast | null = null;
let timer: ReturnType<typeof setTimeout> | undefined;
let nextId = 1;
const listeners = new Set<() => void>();
const changed = () => listeners.forEach((fn) => fn());
const subscribe = (fn: () => void) => {
  listeners.add(fn);
  return () => listeners.delete(fn);
};

/** Asks in a dialog; answers true when the person goes ahead. */
export function confirmDialog(o: Omit<Ask, 'answer'>): Promise<boolean> {
  asking?.answer(false);
  return new Promise((resolve) => {
    asking = {
      ...o,
      answer: (yes) => {
        asking = null;
        changed();
        resolve(yes);
      },
    };
    changed();
  });
}

const toastMs = 6000;

/** Shows a message with one action for a few seconds. A toast already showing closes first. */
export function showToast(o: Omit<Toast, 'id'>) {
  closeToast();
  toast = { ...o, id: nextId++ };
  timer = setTimeout(closeToast, toastMs);
  changed();
}

/** Closes the toast as if it timed out. */
export function closeToast() {
  const was = toast;
  dropToast();
  was?.onClose();
}

/** Takes the toast away without running anything. */
export function dropToast() {
  clearTimeout(timer);
  if (!toast) return;
  toast = null;
  changed();
}

/** Where dialogs and toasts appear; once, at the top of the app. */
export function Overlays() {
  const ask = useSyncExternalStore(subscribe, () => asking);
  const shown = useSyncExternalStore(subscribe, () => toast);
  const dialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const d = dialog.current;
    if (!d) return;
    if (ask && !d.open) d.showModal();
    if (!ask && d.open) d.close();
  }, [ask]);

  return (
    <>
      <dialog
        ref={dialog}
        className="ask"
        aria-labelledby="ask-title"
        // Escape, or the back button on Android.
        onCancel={(e) => {
          e.preventDefault();
          ask?.answer(false);
        }}
        // A tap outside the box.
        onClick={(e) => {
          if (e.target === dialog.current) ask?.answer(false);
        }}
      >
        {ask && (
          <div className="stack">
            <div className="stack tight">
              <h2 id="ask-title">{ask.title}</h2>
              {ask.body && <p className="muted">{ask.body}</p>}
            </div>
            <div className="row end">
              <button type="button" className="secondary" autoFocus onClick={() => ask.answer(false)}>
                {t('Cancel')}
              </button>
              <button type="button" className={ask.danger ? 'danger' : ''} onClick={() => ask.answer(true)}>
                {ask.confirm}
              </button>
            </div>
          </div>
        )}
      </dialog>
      {shown && (
        <div className="toast" role="status" key={shown.id}>
          <span className="grow">{shown.message}</span>
          <button
            type="button"
            className="link"
            onClick={() => {
              dropToast();
              shown.onAction();
            }}
          >
            {shown.action}
          </button>
        </div>
      )}
    </>
  );
}

/** Grey bars in the shape of what's coming, while a page loads for the first time. */
export function Skeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="skeleton" role="progressbar" aria-label={t('Loading…')}>
      {Array.from({ length: rows }, (_, i) => (
        <span key={i} />
      ))}
    </div>
  );
}
