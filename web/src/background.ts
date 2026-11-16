// What the Android app does while it's closed (android/…/RemindersPlugin.java):
// asking "Start bus alarm?" when a shift ends, and telling one of you when
// the other changes something. The web can do neither, so on the web all of
// this does nothing. Both are off until switched on, per phone: the bus
// question on the Bus tab (it's for whoever's shifts they are), the other on
// the Us tab.
import { App as CapApp } from '@capacitor/app';
import { registerPlugin } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
import { api, type ActivityEntry } from './api';
import { addMonths } from './features/shifts/codes';
import { isApp, server } from './native';

const reminders = registerPlugin<{
  /** When to ask (ms since 1970; none turns it off) and the stop's name. Asks to allow notifications when on. */
  setShiftPrompts(o: { on: boolean; ends: number[]; stop: string }): Promise<{ notifications: boolean }>;
  /** Looks for the other person's changes about every half hour; seen is the newest change this phone has shown. */
  setActivityAlerts(o: { on: boolean; server: string; seen: number }): Promise<{ notifications: boolean }>;
}>('Reminders');

const promptKey = 'bus-prompt';
const alertsKey = 'activity-alerts';

async function isOn(key: string): Promise<boolean> {
  return isApp && (await Preferences.get({ key })).value === '1';
}

async function keep(key: string, on: boolean) {
  if (on) await Preferences.set({ key, value: '1' });
  else await Preferences.remove({ key });
}

export const shiftPromptsOn = () => isOn(promptKey);
export const activityAlertsOn = () => isOn(alertsKey);

/**
 * Gives the phone the times shifts end from now on (last, this and next
 * month's), for it to ask at each. False if notifications aren't allowed.
 */
async function sendShiftEnds(): Promise<boolean> {
  const [cur, stop] = await Promise.all([api.shifts(), api.busStop()]);
  const around = await Promise.all([api.shifts(addMonths(cur.month, -1)), api.shifts(addMonths(cur.month, 1))]);
  const now = Date.now();
  const ends = [cur, ...around]
    .flatMap((m) => m.days)
    .filter((d) => d.kind === 'work' && d.end)
    .map((d) => Date.parse(d.end!))
    .filter((ms) => ms > now)
    .sort((a, b) => a - b);
  // Without a stop there's nothing to start.
  const { notifications } = await reminders.setShiftPrompts({ on: true, ends: stop.set ? ends : [], stop: stop.name });
  return notifications;
}

/** Turns the question at the end of a shift on or off. Answers whether it's on now. */
export async function setShiftPrompts(on: boolean): Promise<boolean> {
  if (!isApp) return false;
  if (!on) {
    await keep(promptKey, false);
    await reminders.setShiftPrompts({ on: false, ends: [], stop: '' });
    return false;
  }
  const allowed = await sendShiftEnds();
  await keep(promptKey, allowed);
  return allowed;
}

/** The newest change's id, 0 if there are none. */
export function newest(list: ActivityEntry[]): number {
  return list.reduce((max, e) => Math.max(max, e.id), 0);
}

/** Turns notifications about the other person's changes on or off. Answers whether they're on now. */
export async function setActivityAlerts(on: boolean): Promise<boolean> {
  if (!isApp) return false;
  if (!on) {
    await keep(alertsKey, false);
    await reminders.setActivityAlerts({ on: false, server, seen: 0 });
    return false;
  }
  const { notifications } = await reminders.setActivityAlerts({ on: true, server, seen: newest(await api.activity()) });
  await keep(alertsKey, notifications);
  return notifications;
}

let last = 0;

/**
 * Brings the phone up to date with what's switched on: the shift ends as
 * they are now, and what's already been seen here. Runs when the app opens
 * and comes back into view (at most every few minutes), and right after
 * shifts or the stop change (now).
 */
export async function syncBackground(now = false): Promise<void> {
  if (!isApp || (!now && Date.now() - last < 5 * 60 * 1000)) return;
  last = Date.now();
  try {
    if (await shiftPromptsOn()) await sendShiftEnds();
    if (await activityAlertsOn()) await reminders.setActivityAlerts({ on: true, server, seen: newest(await api.activity()) });
  } catch {
    // Offline, or signed out: the next time will do.
  }
}

/** Keeps the phone up to date while someone is signed in; returns how to stop. */
export function keepBackgroundSynced(): () => void {
  if (!isApp) return () => {};
  void syncBackground();
  const resume = CapApp.addListener('resume', () => void syncBackground());
  return () => {
    resume.then((l) => l.remove());
  };
}
