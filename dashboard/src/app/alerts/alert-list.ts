import { withoutOperator } from '../ack/note';
import { Acknowledgement, Alert, AlertNotice } from '../data/queries';

/**
 * What the recent alerts panel shows, newest first, and whether older alerts exist: at most one page until an older
 * page is loaded, and never cut after that.
 */
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
  /**
   * Set once Load older is used: the alerts below the poll's page, newest first, and the cursor after the oldest of
   * them. From then on the list is never cut, so nothing the viewer has seen leaves it.
   */
  readonly older?: Older;
}

export interface Older {
  readonly items: readonly Alert[];
  readonly next: string | null;
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
  if (!list.older) {
    return { poll: page, pending };
  }
  // Alerts the new page pushed off its end were below none of it, so they move to the top of the older ones.
  const pushedOff = (list.poll?.items ?? []).filter((a) => !listed.has(a.event_id));
  const kept = new Set(pushedOff.map((a) => a.event_id));
  const older = {
    items: [...pushedOff, ...list.older.items.filter((a) => !kept.has(a.event_id))],
    next: list.older.next,
  };
  return { poll: page, pending, older };
}

/**
 * Starts showing older alerts: the list stops being cut, and `next` is the cursor after the oldest alert shown, the
 * poll's last item once the cut is lifted. Unchanged when older alerts are already shown.
 */
export function withOlderStarted(list: LiveList): LiveList {
  if (!list.poll || list.older) {
    return list;
  }
  return { ...list, older: { items: [], next: list.poll.next } };
}

/** Adds an older page below the oldest alert shown, with the cursor after it. */
export function withOlderPage(
  list: LiveList,
  page: { items: Alert[]; next: string | null },
): LiveList {
  if (!list.older) {
    return list;
  }
  const items = [...list.older.items, ...page.items];
  return { ...list, older: { items, next: page.next } };
}

/** Back to the first page, as on a filter change or a return to the console. */
export function withoutOlder(list: LiveList): LiveList {
  if (!list.older) {
    return list;
  }
  const { older: _, ...rest } = list;
  return rest;
}

/**
 * The panel's list: pending notices, then the poll's items, then any older pages, one entry per event_id. Cut to
 * the page size until an older page is asked for, and never cut after that.
 */
export function shown(list: LiveList, pageSize: number): AlertList | undefined {
  if (!list.poll) {
    return undefined;
  }
  const seen = new Set<string>();
  const all: Alert[] = [];
  const older = list.older?.items ?? [];
  for (const alert of [...list.pending.map((p) => p.alert), ...list.poll.items, ...older]) {
    if (!seen.has(alert.event_id)) {
      seen.add(alert.event_id);
      all.push(alert);
    }
  }
  if (list.older) {
    return { items: all, more: list.older.next !== null };
  }
  return {
    items: all.slice(0, pageSize),
    more: list.poll.next !== null || all.length > pageSize,
  };
}

/** Applies `change` to every alert the list holds, wherever it is: pending, in the poll's page or in older pages. */
function mapAlerts(list: LiveList, change: (a: Alert) => Alert): LiveList {
  const next: LiveList = {
    ...list,
    pending: list.pending.map((p) => ({ ...p, alert: change(p.alert) })),
  };
  if (list.poll) {
    (next as { poll: LiveList['poll'] }).poll = {
      ...list.poll,
      items: list.poll.items.map(change),
    };
  }
  if (list.older) {
    (next as { older: Older }).older = { ...list.older, items: list.older.items.map(change) };
  }
  return next;
}

/**
 * Sets the acknowledgement of the alerts named in `acks`, by event_id, leaving which alerts are listed and their
 * order as they are.
 */
export function withAcknowledgements(
  list: LiveList,
  acks: ReadonlyMap<string, Acknowledgement | null>,
): LiveList {
  return mapAlerts(list, (a) =>
    acks.has(a.event_id) ? { ...a, acknowledgement: acks.get(a.event_id)! } : a,
  );
}

/** Every acknowledgement as an anonymous viewer gets it, with no principal and no note. */
export function withoutOperatorFields(list: LiveList): LiveList {
  return mapAlerts(list, (a) => {
    const ack = withoutOperator(a.acknowledgement);
    return ack === a.acknowledgement ? a : { ...a, acknowledgement: ack };
  });
}
