import { useState } from 'react';
import { t, useLanguage } from '../i18n';
import { pickedTheme, setTheme, type Theme } from '../theme';

/** Picks light or dark, or follows the device (the empty choice). */
export function ThemePicker() {
  useLanguage();
  const [theme, setPicked] = useState(pickedTheme);
  return (
    <select
      className="language"
      aria-label={t('Appearance')}
      value={theme ?? ''}
      onChange={(e) => {
        const next = (e.target.value || null) as Theme | null;
        setTheme(next);
        setPicked(next);
      }}
    >
      <option value="">{t('Auto')}</option>
      <option value="light">{t('Light')}</option>
      <option value="dark">{t('Dark')}</option>
    </select>
  );
}
