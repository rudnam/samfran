import { useEffect, useState, type FormEvent } from 'react';
import {
  api,
  errorMessage,
  type DayMeals,
  type MealDayKey,
  type MealReminders,
  type MealRule,
  type MealRuleMode,
  type ShiftSettings,
} from '../../api';
import { BackLink } from '../../components/BackLink';
import { ErrorText } from '../../components/ErrorText';
import { useLeaveGuard } from '../../components/LeaveGuard';
import { Skeleton } from '../../components/Overlay';
import { t } from '../../i18n';
import { href } from '../../router';
import { defaultMealReminders, MEAL_DAY_KEYS, MEAL_KEYS, MEAL_OFFSETS, mergeMealReminders, nextDayMeals, type MealKey } from './meals';

const SHIFT_LABEL: Record<MealDayKey, () => string> = {
  Early: () => t('Early (早番)'),
  Middle: () => t('Middle (中番)'),
  Late: () => t('Late (遅番)'),
  Night: () => t('Night (夜勤)'),
  Off: () => t('Day off (休み・その他)'),
};

const MEAL_LABEL: Record<MealKey, () => string> = {
  breakfast: () => t('Breakfast'),
  lunch: () => t('Lunch'),
  dinner: () => t('Dinner'),
};

function formatOffset(minutes: number): string {
  if (minutes % 60 === 0) {
    const h = minutes / 60;
    return h === 1 ? t('1 hour') : t('{n} hours', { n: h });
  }
  return t('{n} min', { n: minutes });
}

function defaultRuleFor(mode: MealRuleMode, prev: MealRule): MealRule {
  switch (mode) {
    case 'fixed':
      return { mode: 'fixed', time: prev.time || '12:00' };
    case 'before_start':
      return { mode: 'before_start', minutes: prev.minutes || 120 };
    case 'after_end':
      return { mode: 'after_end', minutes: prev.minutes || 60 };
    case 'off':
      return { mode: 'off' };
  }
}

export function ShiftMealRemindersPage() {
  const [settings, setSettings] = useState<ShiftSettings | null>(null);
  const [meals, setMeals] = useState<MealReminders>(defaultMealReminders);
  const [savedForm, setSavedForm] = useState('');
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);

  const dirty = !!settings && JSON.stringify(meals) !== savedForm;
  useLeaveGuard(dirty);

  function take(next: ShiftSettings) {
    const merged = mergeMealReminders(next.meal_reminders);
    setSettings(next);
    setMeals(merged);
    setSavedForm(JSON.stringify(merged));
  }

  useEffect(() => {
    api.shiftSettings().then(take, (err) => setError(errorMessage(err)));
  }, []);

  function updateRule(shiftKey: MealDayKey, mealKey: MealKey, rule: MealRule) {
    setStatus('');
    setMeals((prev) => ({
      ...prev,
      [shiftKey]: { ...prev[shiftKey], [mealKey]: rule },
    }));
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    setStatus('');
    try {
      const updated = await api.saveShiftSettings({ meal_reminders: meals });
      take(updated);
      setStatus(t('Saved.'));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  function resetDefaults() {
    setStatus('');
    setMeals(defaultMealReminders());
  }

  return (
    <section className="stack">
      <BackLink href={href('shifts/settings')} label="Shift settings" />
      <h1 className="display">{t('Meal reminders')}</h1>
      <p className="hint">{t('Shared meal times for each shift. Turn notifications on or off per phone in the Android app.')}</p>
      {!settings && !error && <Skeleton rows={4} />}
      <ErrorText message={error} />
      {settings && (
        <form className="stack" onSubmit={save}>
          {MEAL_DAY_KEYS.map((shiftKey) => {
            const dayMeals: DayMeals = meals[shiftKey];
            const working = shiftKey !== 'Off';
            const nextDays = nextDayMeals(shiftKey, dayMeals);
            const heading = SHIFT_LABEL[shiftKey]();
            return (
              <section key={shiftKey} className="card stack meal-section" aria-label={heading}>
                <h2>{heading}</h2>
                {MEAL_KEYS.map((mealKey) => {
                  const rule = dayMeals[mealKey];
                  const mealLabel = MEAL_LABEL[mealKey]();
                  const isNextDay = nextDays.has(mealKey);
                  return (
                    <div key={mealKey} className="meal-row">
                      <span className="meal-name">{mealLabel}</span>
                      <div className="meal-controls">
                        <select
                          aria-label={t('{shift} {meal} mode', { shift: heading, meal: mealLabel })}
                          value={rule.mode}
                          onChange={(e) => updateRule(shiftKey, mealKey, defaultRuleFor(e.target.value as MealRuleMode, rule))}
                        >
                          <option value="fixed">{t('At time')}</option>
                          {working && <option value="before_start">{t('Before shift')}</option>}
                          {working && <option value="after_end">{t('After shift')}</option>}
                          <option value="off">{t('Off')}</option>
                        </select>
                        {rule.mode === 'fixed' && (
                          <div className="meal-time-wrap">
                            <input
                              type="time"
                              aria-label={t('{shift} {meal} time', { shift: heading, meal: mealLabel })}
                              value={rule.time ?? '12:00'}
                              onChange={(e) => updateRule(shiftKey, mealKey, { mode: 'fixed', time: e.target.value })}
                            />
                            {isNextDay && <span className="meal-next-day">{t('Next day')}</span>}
                          </div>
                        )}
                        {(rule.mode === 'before_start' || rule.mode === 'after_end') && (
                          <select
                            aria-label={t('{shift} {meal} offset', { shift: heading, meal: mealLabel })}
                            value={rule.minutes ?? 120}
                            onChange={(e) =>
                              updateRule(shiftKey, mealKey, { mode: rule.mode, minutes: Number(e.target.value) })
                            }
                          >
                            {MEAL_OFFSETS.map((m) => (
                              <option key={m} value={m}>
                                {formatOffset(m)}
                              </option>
                            ))}
                          </select>
                        )}
                      </div>
                    </div>
                  );
                })}
              </section>
            );
          })}
          <div className="row wrap sticky-actions">
            <button type="button" className="secondary" disabled={busy} onClick={resetDefaults}>
              {t('Reset to defaults')}
            </button>
            <span className="hint grow" role="status">
              {status}
            </span>
            <button type="submit" disabled={busy}>
              {t('Save')}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
