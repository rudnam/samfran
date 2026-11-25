// What the two of you said about a place you've been to, oldest first, and a
// box to add to it. Each comment is its writer's: only they can delete it.
import { useState, type FormEvent } from 'react';
import { api, errorMessage, type Restaurant } from '../../api';
import { confirmDialog } from '../../components/Overlay';
import { t } from '../../i18n';
import { locale } from '../../language';
import { Avatar, personTone, useSpace } from '../../space';
import { ErrorText } from '../../components/ErrorText';

const maxComment = 1000; // as the server's

export function Comments({ place, onChange }: { place: Restaurant; onChange: (r: Restaurant) => void }) {
  const { space } = useSpace();
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const comments = place.comments ?? [];

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

  async function post(e: FormEvent) {
    e.preventDefault();
    if (draft.trim() && (await run(() => api.addComment(place.id, draft)))) setDraft('');
  }

  async function remove(id: number) {
    const yes = await confirmDialog({ title: t('Delete your comment?'), confirm: t('Delete'), danger: true });
    if (yes) await run(() => api.deleteComment(place.id, id));
  }

  return (
    <div className="card stack">
      <h2>{t('Comments')}</h2>
      {comments.length === 0 ? (
        <p className="muted">{t('No comments yet. How was it?')}</p>
      ) : (
        <ul className="comments" aria-label={t('Comments')}>
          {comments.map((c) => (
            <li key={c.id}>
              <Avatar name={c.by} tone={personTone(space, c.by)} />
              <div className="grow stack tight">
                <p className="comment-head">
                  <strong>{c.me ? t('You') : c.by}</strong>
                  <time className="muted small" dateTime={c.created_at}>
                    {new Date(c.created_at).toLocaleString(locale(), { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })}
                  </time>
                  {c.me && (
                    <button type="button" className="link quiet small" disabled={busy} onClick={() => remove(c.id)} aria-label={t('Delete your comment')}>
                      {t('Delete')}
                    </button>
                  )}
                </p>
                <p className="prewrap">{c.body}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
      <form className="stack tight" onSubmit={post}>
        <textarea
          aria-label={t('Add a comment')}
          placeholder={t('Add a comment')}
          rows={2}
          maxLength={maxComment}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
        <ErrorText message={error} />
        <button type="submit" className="end" disabled={busy || !draft.trim()}>
          {t('Post')}
        </button>
      </form>
    </div>
  );
}
