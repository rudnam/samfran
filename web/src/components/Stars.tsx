import { t } from '../i18n';

/** Five stars to tap. Tapping the current rating again clears it. */
export function StarPicker({ value, onChange, disabled }: { value: number; onChange: (stars: number) => void; disabled?: boolean }) {
  return (
    <div className="star-picker" role="group" aria-label={t('Your rating')}>
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          type="button"
          className={n <= value ? 'on' : ''}
          aria-label={t('{n} stars', { n })}
          aria-pressed={n === value}
          disabled={disabled}
          onClick={() => onChange(n === value ? 0 : n)}
        >
          <StarIcon />
        </button>
      ))}
    </div>
  );
}

function StarIcon() {
  return (
    <svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true">
      <path d="M12 2.8l2.8 5.9 6.4.8-4.7 4.4 1.2 6.4L12 17.2l-5.7 3.1 1.2-6.4-4.7-4.4 6.4-.8z" />
    </svg>
  );
}
