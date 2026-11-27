import type { DayMeals, MealDayKey, MealReminders, MealRule } from '../../api';

export const MEAL_DAY_KEYS: MealDayKey[] = ['Early', 'Middle', 'Late', 'Night', 'Off'];

export const MEAL_KEYS = ['breakfast', 'lunch', 'dinner'] as const;
export type MealKey = (typeof MEAL_KEYS)[number];

export const MEAL_OFFSETS = [15, 30, 45, 60, 90, 120, 150, 180, 210, 240, 300, 360];

export function defaultMealReminders(): MealReminders {
  return {
    Early: {
      breakfast: { mode: 'before_start', minutes: 120 },
      lunch: { mode: 'fixed', time: '11:00' },
      dinner: { mode: 'fixed', time: '18:00' },
    },
    Middle: {
      breakfast: { mode: 'fixed', time: '08:00' },
      lunch: { mode: 'fixed', time: '13:00' },
      dinner: { mode: 'fixed', time: '19:00' },
    },
    Late: {
      breakfast: { mode: 'fixed', time: '10:00' },
      lunch: { mode: 'fixed', time: '15:00' },
      dinner: { mode: 'fixed', time: '00:00' },
    },
    Night: {
      breakfast: { mode: 'fixed', time: '20:00' },
      lunch: { mode: 'fixed', time: '03:00' },
      dinner: { mode: 'fixed', time: '08:00' },
    },
    Off: {
      breakfast: { mode: 'fixed', time: '08:00' },
      lunch: { mode: 'fixed', time: '13:00' },
      dinner: { mode: 'fixed', time: '19:00' },
    },
  };
}

export function mergeMealReminders(saved?: Partial<MealReminders> | null): MealReminders {
  const defs = defaultMealReminders();
  if (!saved) return defs;
  for (const k of MEAL_DAY_KEYS) {
    const v = saved[k];
    if (v) defs[k] = { breakfast: { ...v.breakfast }, lunch: { ...v.lunch }, dinner: { ...v.dinner } };
  }
  return defs;
}

const REPRESENTATIVE_START: Record<MealDayKey, number | null> = {
  Early: 7,
  Middle: 9,
  Late: 13,
  Night: 22,
  Off: null,
};

function parseMinutes(time?: string): number {
  const [h, m] = (time ?? '12:00').split(':').map(Number);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return 12 * 60;
  return h * 60 + m;
}

/**
 * Returns which fixed meals roll over to the next calendar day under the
 * chronological rollover rule, using representative shift start hours.
 */
export function nextDayMeals(shiftKey: MealDayKey, dayMeals: DayMeals): Set<MealKey> {
  const startHour = REPRESENTATIVE_START[shiftKey];
  const startMin = startHour === null ? null : startHour * 60;
  const endMin = startMin === null ? null : startMin + 9 * 60;
  let cursor: number | null = null;
  const next = new Set<MealKey>();

  for (const meal of MEAL_KEYS) {
    const rule: MealRule = dayMeals[meal];
    if (rule.mode === 'off') continue;
    if (rule.mode === 'before_start') {
      if (startMin !== null) cursor = startMin - (rule.minutes ?? 120);
      continue;
    }
    if (rule.mode === 'after_end') {
      if (endMin !== null) {
        cursor = endMin + (rule.minutes ?? 60);
        if (cursor >= 24 * 60) next.add(meal);
      }
      continue;
    }
    let candidate = parseMinutes(rule.time);
    if (cursor !== null) {
      while (candidate <= cursor) candidate += 24 * 60;
    } else if (startMin !== null && candidate < startMin - 6 * 60) {
      candidate += 24 * 60;
    }
    if (candidate >= 24 * 60) next.add(meal);
    cursor = candidate;
  }
  return next;
}
