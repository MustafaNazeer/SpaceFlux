import { Alert, AlertNotice } from '../data/queries';

/** What the recent alerts panel shows: at most one page, newest first, and whether older alerts exist. */
export interface AlertList {
  items: Alert[];
  more: boolean;
}

interface Pending {
  alert: Alert;
  /** Poll results that have arrived since the notice without listing it. */
  missedBy: number;
}

/**
 * The newest poll result, and the notices that arrived since and that no poll has listed yet, newest first. The
 * poll is the source of truth: once it lists a notice's alert, its entry and its place come from the poll.
 */
export interface LiveList {
  readonly poll?: { items: Alert[]; next: string | null };
  readonly pending: readonly Pending[];
}

export const EMPTY_LIVE: LiveList = { pending: [] };

/** A list item for a notice. A notice has no acknowledgement yet and is never a screening run. */
export function noticeAlert(notice: AlertNotice): Alert {
  return { ...notice, screening_run: null, acknowledgement: null };
}

function known(list: LiveList, eventId: string): boolean {
  return (
    list.pending.some((p) => p.alert.event_id === eventId) ||
    (list.poll?.items ?? []).some((a) => a.event_id === eventId)
  );
}

/**
 * Adds a notice in front of the list, or leaves the list as it is when the alert is already there. `added` is the
 * new item, and is what gets announced, so a repeated notice is never announced twice.
 */
export function withNotice(
  list: LiveList,
  notice: AlertNotice,
  pageSize: number,
): { list: LiveList; added: Alert | null } {
  if (known(list, notice.event_id)) {
    return { list, added: null };
  }
  const alert = noticeAlert(notice);
  const pending = [{ alert, missedBy: 0 }, ...list.pending].slice(0, pageSize);
  return { list: { ...list, pending }, added: alert };
}

/**
 * Takes a poll result as the new list. A notice the poll lists is dropped from the pending ones, so the poll's
 * order wins. A notice it does not list stays through that one result, which may have been read before the alert
 * was stored, and is dropped when a second result still does not list it.
 */
export function withPoll(list: LiveList, page: { items: Alert[]; next: string | null }): LiveList {
  const listed = new Set(page.items.map((a) => a.event_id));
  const pending = list.pending
    .filter((p) => !listed.has(p.alert.event_id))
    .map((p) => ({ ...p, missedBy: p.missedBy + 1 }))
    .filter((p) => p.missedBy < 2);
  return { poll: page, pending };
}

/** The panel's list: pending notices, then the poll's items, one entry per event_id, cut to the page size. */
export function shown(list: LiveList, pageSize: number): AlertList | undefined {
  if (!list.poll) {
    return undefined;
  }
  const seen = new Set<string>();
  const all: Alert[] = [];
  for (const alert of [...list.pending.map((p) => p.alert), ...list.poll.items]) {
    if (!seen.has(alert.event_id)) {
      seen.add(alert.event_id);
      all.push(alert);
    }
  }
  return {
    items: all.slice(0, pageSize),
    more: list.poll.next !== null || all.length > pageSize,
  };
}
