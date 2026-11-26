// The two of you: names, inviting your partner, language and signing out.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Me } from '../../api';
import { PLAY_STORE_URL } from '../../playStore';
import { ActivityCard } from './ActivityCard';
import { LanguagePicker } from '../../components/LanguagePicker';
import { ThemePicker } from '../../components/ThemePicker';
import { t } from '../../i18n';
import { Avatar, useSpace } from '../../space';
import { ErrorText } from '../../components/ErrorText';
import { Skeleton } from '../../components/Overlay';

// The Android app is no use on an iPhone or iPad (which can say it's a Mac).
const iPhone = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

export function UsPage({ me, onSignOut }: { me: Me; onSignOut: () => void }) {
  const { space, setSpace } = useSpace();
  const self = space?.members.find((m) => m.me);
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (self) setName(self.name);
  }, [self?.name]); // eslint-disable-line react-hooks/exhaustive-deps

  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await fn();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  function saveName(e: FormEvent) {
    e.preventDefault();
    run(async () => {
      setSpace(await api.setMyName(name));
      setSaved(true);
    });
  }


  const room = space ? space.members.length + space.invites.length < 2 : false;
  return (
    <section className="stack">
      <h1 className="display">{t('Us')}</h1>
      <ErrorText message={error} />

      <div className="card stack">
        <h2>{t('The two of you')}</h2>
        {!space && <Skeleton rows={1} />}
        {space && (
          <ul className="people" aria-label={t('Members')}>
            {space.members.map((m, i) => (
              <li key={m.email}>
                <Avatar name={m.name} tone={i === 1 ? 'b' : 'a'} size="lg" />
                <span className="grow">
                  <strong>{m.name}</strong>
                  {m.me && <span className="muted"> · {t('you')}</span>}
                  <span className="muted block small">{m.email}</span>
                </span>
              </li>
            ))}
            {space.invites.map((i) => (
              <li key={i.email}>
                <span className="avatar pending" aria-hidden="true">
                  ?
                </span>
                <span className="grow">
                  <strong>{i.email}</strong>
                  <span className="muted block small">{t('Invited')}</span>
                </span>
                <button type="button" className="link quiet" disabled={busy} onClick={() => run(async () => setSpace(await api.uninvite(i.email)))}>
                  {t('Cancel')}
                </button>
              </li>
            ))}
          </ul>
        )}
        {room && !me.demo && space && <InviteLink open={space.invite_link} onChange={() => api.space().then(setSpace, () => {})} />}
      </div>

      <form className="card stack" onSubmit={saveName}>
        <h2>{t('Your name')}</h2>
        <span className="row">
          <input
            aria-label={t('Your name')}
            maxLength={40}
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              setSaved(false);
            }}
          />
          <button type="submit" disabled={busy || !space || name === self?.name}>
            {t('Save')}
          </button>
        </span>
        <span className="hint" role="status">
          {saved && t('Saved.')}
        </span>
      </form>

      {!iPhone && (
        <section className="card get-app" aria-labelledby="get-app-title">
          <img src="/icon.svg" alt="" width="52" height="52" />
          <span className="get-app-text">
            <strong id="get-app-title" className="block">{t('SamFran for Android')}</strong>
            <span className="muted small">{t('Ride alarm, locked apps and home-screen widgets')}</span>
          </span>
          <a className="button" href={PLAY_STORE_URL} target="_blank" rel="noopener">
            {t('Get it on Google Play')}
          </a>
        </section>
      )}

      <ActivityCard />

      <div className="card stack device">
        <h2>{t('This device')}</h2>
        <div className="row spread">
          <span>{t('Language')}</span>
          <LanguagePicker />
        </div>
        <div className="row spread">
          <span>{t('Appearance')}</span>
          <ThemePicker />
        </div>
        <div className="row spread">
          <span className="muted small">{me.demo ? t('Demo') : me.email}</span>
          <button type="button" className="secondary" onClick={onSignOut}>
            {me.demo ? t('End demo') : t('Sign out')}
          </button>
        </div>
      </div>
    </section>
  );
}

function InviteLink({ open, onChange }: { open: { expires_at: string } | null; onChange: () => void }) {
  const [url, setUrl] = useState('');
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const until = (iso: string) => new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });

  async function make() {
    setBusy(true);
    setError('');
    try {
      const l = await api.createInviteLink();
      setUrl(`${location.origin}${l.path}`);
      setCopied(false);
      onChange();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    setBusy(true);
    try {
      await api.cancelInviteLink();
      setUrl('');
      onChange();
    } finally {
      setBusy(false);
    }
  }

  async function share() {
    if (navigator.share) {
      await navigator.share({ title: 'SamFran', text: t('Join me on SamFran'), url }).catch(() => {});
    } else {
      await navigator.clipboard?.writeText(url).then(() => setCopied(true), () => {});
    }
  }

  return (
    <div className="stack tight">
      <span className="field">{t('Invite your partner')}</span>
      {url ? (
        <>
          <div className="row">
            <input readOnly aria-label={t('Invite link')} value={url} onFocus={(e) => e.target.select()} />
            <button type="button" className="secondary" onClick={() => navigator.clipboard?.writeText(url).then(() => setCopied(true), () => {})}>
              {copied ? t('Copied') : t('Copy')}
            </button>
          </div>
          <div className="row wrap">
            {'share' in navigator && (
              <button type="button" onClick={share}>
                {t('Share')}
              </button>
            )}
            <button type="button" className="link quiet" disabled={busy} onClick={cancel}>
              {t('Cancel link')}
            </button>
          </div>
          <span className="hint">{t('Works once, until {date}.', { date: until(open?.expires_at ?? new Date(Date.now() + 7 * 864e5).toISOString()) })}</span>
        </>
      ) : open ? (
        <>
          <span className="hint">{t('An invite link is open until {date}. Make a new one to see its address again (the old one stops working).', { date: until(open.expires_at) })}</span>
          <div className="row wrap">
            <button type="button" disabled={busy} onClick={make}>
              {t('Make a new link')}
            </button>
            <button type="button" className="link quiet" disabled={busy} onClick={cancel}>
              {t('Cancel link')}
            </button>
          </div>
        </>
      ) : (
        <>
          <button type="button" disabled={busy} onClick={make}>
            {t('Create invite link')}
          </button>
        </>
      )}
      <ErrorText message={error} />
    </div>
  );
}
