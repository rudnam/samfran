// Connect: Claude (a connector) asks to reach samfran, and the signed-in
// person allows or denies it. The server sends the browser here from
// /oauth/authorize (internal/mcp/oauth.go) and, once they answer, back to
// Claude. As in RudWallet.
import { useEffect, useState } from 'react';
import { api, errorMessage, type MCPClientInfo } from '../../api';
import { t, tx } from '../../i18n';
import { href, type Route } from '../../router';

/** Where a Connect request is kept while Google sign-in leaves the page. */
const pendingKey = 'samfran.connect';

/** Remembers a Connect request that is waiting for the person to sign in. */
export function rememberConnect() {
  try {
    if (location.hash.startsWith('#/connect?')) sessionStorage.setItem(pendingKey, location.hash);
  } catch {
    // Without storage, Google sign-in lands on the list; Claude can start again.
  }
}

/** After signing in, goes back to a Connect request started before. */
export function resumeConnect() {
  try {
    const pending = sessionStorage.getItem(pendingKey);
    sessionStorage.removeItem(pendingKey);
    if (pending && !location.hash.startsWith('#/connect?')) location.replace(location.pathname + pending);
  } catch {
    // nothing to resume
  }
}

export function ConnectPage({ route, email }: { route: Route; email: string }) {
  const p = route.params;
  const request = {
    client_id: p.get('client_id') ?? '',
    redirect_uri: p.get('redirect_uri') ?? '',
    code_challenge: p.get('code_challenge') ?? '',
    state: p.get('state') ?? '',
  };
  const [client, setClient] = useState<MCPClientInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.mcpClient(request.client_id, request.redirect_uri).then(setClient, (err) => setError(errorMessage(err)));
  }, [request.client_id, request.redirect_uri]);

  async function answer(allow: boolean) {
    setBusy(true);
    setError('');
    try {
      const { redirect_to } = await api.mcpAuthorize({ ...request, allow });
      location.replace(redirect_to);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <section className="card stack connect">
      {!client && !error && <p className="muted">{t('Loading…')}</p>}
      {client && (
        <>
          <h1 className="display">{t('Connect {app}', { app: client.client_name })}</h1>
          <p>
            {tx('{app} is asking to reach samfran, signed in as {email}. It will be able to:', {
              app: <strong>{client.client_name}</strong>,
              email: <strong>{email}</strong>,
            })}
          </p>
          <ul className="connect-list">
            <li>{t('see and save the shifts, e.g. from a photo of the sheet you share with it')}</li>
            <li>{t('see restaurants, add them, log visits, and set your rating')}</li>
          </ul>
          <p className="muted small">
            {t('It can’t delete anything or see your password. You can disconnect it at any time in Us → Claude.')}
          </p>
          <p className="muted small">{tx('You’ll go back to {host}.', { host: <strong>{client.redirect_host}</strong> })}</p>
          <div className="row end">
            <button type="button" className="secondary" disabled={busy} onClick={() => answer(false)}>
              {t('Deny')}
            </button>
            <button type="button" disabled={busy} onClick={() => answer(true)}>
              {t('Allow')}
            </button>
          </div>
        </>
      )}
      {error && (
        <>
          <p role="alert" className="error">
            {error}
          </p>
          <a href={href('home')}>{t('Go to samfran')}</a>
        </>
      )}
    </section>
  );
}
