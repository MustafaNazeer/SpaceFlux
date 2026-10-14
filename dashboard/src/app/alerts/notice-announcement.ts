import { Alert } from '../data/queries';
import { parseUtc, Segment, text } from '../format';
import { alertView } from './alert-view';
import { ListNoun, NOUNS } from './list-end';

/**
 * What the polite live region says for newly listed alerts. Every wording lives in this file. Each sentence reuses
 * the list item's title, so the announcement and the item it adds read the same. <opening> is "New alert" for a
 * single alert and "Top of the list" inside a group:
 *
 * * close approach: "<opening>, close approach: <title>."
 * * level entered: "<opening>, <badge>: <title>."
 * * level changed: "<opening>, <badge>, changed from <scale><previous level>: <title>."; when the trigger is a
 *   revision, "revised from" instead of "changed from". A revision at the same level names no earlier level; its
 *   title already ends "a revised Kp value".
 * * level left (to none, no data or ended): "<opening>. <title>.", since the title carries the level left
 *   ("after G3", "last level R1"). An ended title that cannot carry it (no readable time) gets the lead
 *   "<opening>, last level <scale><previous level>. " instead.
 * * anything else: "<opening>, <badge>: <title>." or, with no badge, "<opening>: <title>."
 *
 * Several alerts at once: "<N> new alerts. Top of the list, <rest of the top alert's sentence>".
 *
 * While the list is held, new alerts wait in the bar above it: "New alert waiting above the list, <rest>" for one
 * and "<N> new alerts waiting above the list. The top one, <rest>" for several. An entry while others already wait
 * gives the total the bar shows: "<k> more new alerts waiting above the list, <N> in all. The top one, <rest>", or
 * "1 more new alert waiting above the list, <N> in all. The new one, <rest>" for one.
 *
 * Summaries name what the list holds under its filter (alerts, close approaches or space weather alerts). Alerts the
 * refresh after a pause brings: "1 <one> arrived while paused." or "<N> <many> arrived while paused.", and
 * "<N> or more <many> arrived while paused." when that refresh could not count them all. Alerts that arrived while
 * the console was not shown: "1 <one> arrived while you were away.", "<N> <many> arrived while you were away.", or
 * "<N> or more <many> arrived while you were away." when the count can be short.
 */
export function noticeAnnouncement(alert: Alert, opening = 'New alert'): Segment[] {
  const view = alertView(alert, 0);
  const l = alert.space_weather_level;
  let lead: string;
  if (alert.close_approach) {
    lead = `${opening}, close approach: `;
  } else if (l && l.previous_state === 'level' && l.previous_derived_level !== null) {
    const previous = `${l.scale}${l.previous_derived_level}`;
    if (l.state !== 'level') {
      const carried = l.state !== 'ended' || !Number.isNaN(parseUtc(l.no_data_since));
      lead = carried ? `${opening}. ` : `${opening}, last level ${previous}. `;
    } else if (l.derived_level !== l.previous_derived_level) {
      const verb = l.trigger === 'revision' ? 'revised from' : 'changed from';
      lead = `${opening}, ${view.badge?.text}, ${verb} ${previous}: `;
    } else {
      lead = `${opening}, ${view.badge?.text}: `;
    }
  } else {
    lead = view.badge ? `${opening}, ${view.badge.text}: ` : `${opening}: `;
  }
  const last = view.title[view.title.length - 1];
  const closed = last?.kind === 'text' && last.text.endsWith('.');
  return [text(lead), ...view.title, ...(closed ? [] : [text('.')])];
}

/** One log entry for alerts listed together, in list order: one alert keeps its own sentence. */
export function groupAnnouncement(alerts: Alert[]): Segment[] {
  if (alerts.length === 1) {
    return noticeAnnouncement(alerts[0]);
  }
  return [
    text(`${alerts.length} new alerts. `),
    ...noticeAnnouncement(alerts[0], 'Top of the list'),
  ];
}

/** "1 {noun.one} arrived while {when}.", "{N} {noun.many} ..." or, when the count can be short, "{N} or more ...". */
function arrived(count: number, more: boolean, noun: ListNoun, when: string): Segment[] {
  if (more) {
    return [text(`${count} or more ${noun.many} arrived while ${when}.`)];
  }
  return [text(`${count} ${count === 1 ? noun.one : noun.many} arrived while ${when}.`)];
}

/** The summary for alerts the first refresh after a pause adds; `more` when it could not count them all. */
export function pausedAnnouncement(count: number, more = false, noun = NOUNS.all): Segment[] {
  return arrived(count, more, noun, 'paused');
}

/**
 * One log entry for alerts that wait above a held list, in list order (F30). `total` is how many the bar holds,
 * these included, so an entry while others already wait gives the count the bar shows (F39).
 */
export function heldAnnouncement(alerts: Alert[], total = alerts.length): Segment[] {
  if (total > alerts.length) {
    const more =
      alerts.length === 1
        ? '1 more new alert waiting above the list'
        : `${alerts.length} more new alerts waiting above the list`;
    return [
      text(`${more}, ${total} in all. `),
      ...noticeAnnouncement(alerts[0], alerts.length === 1 ? 'The new one' : 'The top one'),
    ];
  }
  if (alerts.length === 1) {
    return noticeAnnouncement(alerts[0], 'New alert waiting above the list');
  }
  return [
    text(`${alerts.length} new alerts waiting above the list. `),
    ...noticeAnnouncement(alerts[0], 'The top one'),
  ];
}

/** The summary for alerts that arrived while the console was not shown; `more` when the count can be short. */
export function awayAnnouncement(count: number, more = false, noun = NOUNS.all): Segment[] {
  return arrived(count, more, noun, 'you were away');
}
