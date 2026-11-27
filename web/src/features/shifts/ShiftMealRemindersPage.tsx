import { useEffect, useState, type FormEvent } from 'react';
import {
  api,
  errorMessage,
  type DayMeals,
  type MealDayKey,
  type MealReminders,
  type MealRule,
  type ShiftSettings,
} from '../../api';
import { BackLink } from '../../components/BackLink';
import { ErrorText } from '../../components/ErrorText';
import { Icon } from '../../components/Icon';
import { useLeaveGuard } from '../../components/LeaveGuard';
import { Skeleton } from '../../components/Overlay';
import { t } from '../../i18n';
import { href } from '../../router';
import { defaultMealReminders, MEAL_DAY_KEYS, MEAL_KEYS, mergeMealReminders, nextDayMeals, type MealKey } from './meals';

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

  const defaults = defaultMealReminders();

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
                        {rule.mode === 'fixed' ? (
                          <div className="meal-time-wrap">
                            <input
                              type="time"
                              aria-label={t('{shift} {meal} time', { shift: heading, meal: mealLabel })}
                              value={rule.time ?? '12:00'}
                              onChange={(e) => updateRule(shiftKey, mealKey, { mode: 'fixed', time: e.target.value })}
                            />
                            {isNextDay && <span className="meal-next-day">{t('Next day')}</span>}
                            <button
                              type="button"
                              className="link quiet"
                              aria-label={t('Turn off {shift} {meal}', { shift: heading, meal: mealLabel })}
                              onClick={() => updateRule(shiftKey, mealKey, { mode: 'off' })}
                            >
                              <Icon name="close" size={18} />
                            </button>
                          </div>
                        ) : (
                          <div className="meal-time-wrap">
                            <span className="muted small">{t('Off')}</span>
                            <button
                              type="button"
                              className="link"
                              onClick={() =>
                                updateRule(shiftKey, mealKey, {
                                  mode: 'fixed',
                                  time: defaults[shiftKey][mealKey].time ?? '12:00',
                                })
                              }
                            >
                              {t('Set time')}
                            </button>
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}
              </section>
            );
          })}
          <div className="row wrap">
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
