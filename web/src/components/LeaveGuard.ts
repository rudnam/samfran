// Asking before changes that aren't saved are lost: going to another page
// (a link, a tab, Back) asks in the app's dialog; closing or reloading the
// tab, in the browser's own.
import { useCallback, useEffect, useRef } from 'react';
import { t } from '../i18n';
import { setLeaveGuard } from '../router';
import { confirmDialog } from './Overlay';

/**
 * Guards the page while dirty. Call the returned function once the changes
 * are saved, just before going on, so that move isn't asked about.
 */
export function useLeaveGuard(dirty: boolean): () => void {
  const isDirty = useRef(dirty);
  isDirty.current = dirty;
  const released = useRef(false);
  useEffect(() => {
    const unsaved = () => isDirty.current && !released.current;
    const unload = (e: BeforeUnloadEvent) => {
      if (!unsaved()) return;
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', unload);
    const remove = setLeaveGuard({
      dirty: unsaved,
      ask: () =>
        confirmDialog({
          title: t('Leave without saving?'),
          body: t('What you changed here will be lost.'),
          confirm: t('Leave'),
          danger: true,
        }),
    });
    return () => {
      window.removeEventListener('beforeunload', unload);
      remove();
    };
  }, []);
  return useCallback(() => {
    released.current = true;
  }, []);
}
