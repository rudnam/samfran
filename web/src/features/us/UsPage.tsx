// The two of you: names, inviting your partner, language and signing out.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Downloads, type MCPToken, type Me } from '../../api';
import { isApp, server } from '../../native';
import { ActivityCard } from './ActivityCard';
import { LanguagePicker } from '../../components/LanguagePicker';
import { ThemePicker } from '../../components/ThemePicker';
import { t } from '../../i18n';
import { Avatar, useSpace } from '../../space';

export function UsPage({ me, onSignOut }: { me: Me; onSignOut: () => void }) {
  const { space, setSpace } = useSpace();
  const self = space?.members.find((m) => m.me);
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [apk, setApk] = useState<Downloads['android']>(null);

  useEffect(() => {
    if (!isApp) api.downloads().then((d) => setApk(d.android), () => {});
  }, []);

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
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <div className="card stack">
        <h2>{t('The two of you')}</h2>
        {!space && <p className="muted">{t('Loading…')}</p>}
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
                  <span className="muted block small">{t('Invited: they can sign in with this email to join you.')}</span>
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
          {saved ? t('Saved.') : t('Shown next to your ratings.')}
        </span>
      </form>

      {apk && (
        <div className="card row spread">
          <span className="grow">
            <strong className="block">{t('Get the Android app')}</strong>
            <span className="hint">{t('Share a place from Google Maps to SamFran to add it in one tap.')}</span>
          </span>
          <a className="button" href={apk.url} download>
            {t('Download')}
          </a>
        </div>
      )}

      <ActivityCard />

      {!me.demo && <ClaudeCard />}

      <div className="card stack">
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

/** Claude as a connector: the address to add, and the connections made. */
function ClaudeCard() {
  const [list, setList] = useState<MCPToken[] | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState('');
  const url = `${server || location.origin}/mcp`;

  useEffect(() => {
    api.mcpTokens().then(setList, (err) => setError(errorMessage(err)));
  }, []);

  async function disconnect(id: number) {
    try {
      await api.deleteMCPToken(id);
      setList((l) => l?.filter((x) => x.id !== id) ?? null);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <div className="card stack">
      <h2>{t('Claude')}</h2>
      <p className="muted">
        {t('Connect Claude to SamFran and it can read a photo of the shift sheet on your own Claude plan, save the month after you check it, and add or rate restaurants.')}
      </p>
      <ol className="steps small">
        <li>{t('In Claude (claude.ai), open Settings › Connectors › Add custom connector.')}</li>
        <li>{t('Name it SamFran and paste this address:')}</li>
      </ol>
      <div className="row">
        <input readOnly aria-label={t('Connector address')} value={url} onFocus={(e) => e.target.select()} />
        <button type="button" className="secondary" onClick={() => navigator.clipboard?.writeText(url).then(() => setCopied(true), () => {})}>
          {copied ? t('Copied') : t('Copy')}
        </button>
      </div>
      <span className="hint">{t('Claude then asks you to sign in here and allow it. After that, share a photo of the sheet in a chat and ask it to add the shifts.')}</span>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {list && list.length > 0 && (
        <ul className="people" aria-label={t('Connections')}>
          {list.map((c) => (
            <li key={c.id}>
              <span className="grow">
                <strong>{c.name}</strong>
                <span className="muted block small">
                  {c.last_used_at ? t('Last used {date}', { date: new Date(c.last_used_at).toLocaleDateString() }) : t('Not used yet')}
                </span>
              </span>
              <button type="button" className="link quiet" onClick={() => disconnect(c.id)}>
                {t('Disconnect')}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Inviting your partner: a link to send them, which works once, for seven days. */
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
      setUrl(`${server || location.origin}${l.path}`);
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
          <span className="hint">{t('Send it to them only: it works once, until {date}. They tap it and sign up with Google or an email and password.', { date: until(open?.expires_at ?? new Date(Date.now() + 7 * 864e5).toISOString()) })}</span>
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
          <span className="hint">{t('Send them the link; they tap it and sign up, and see everything here.')}</span>
        </>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </div>
  );
}
