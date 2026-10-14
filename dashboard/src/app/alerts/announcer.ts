import { Alert } from '../data/queries';
import { Segment } from '../format';
import { AlertList } from './alert-list';
import { ListNoun, NOUNS } from './list-end';
import {
  awayAnnouncement,
  groupAnnouncement,
  heldAnnouncement,
  pausedAnnouncement,
} from './notice-announcement';

/** One paragraph of the live log, spoken once when it is added. */
export interface LogEntry {
  id: number;
  segments: Segment[];
  /** The alerts it covers, newest first. */
  eventIds: string[];
}

/**
 * Decides what the live log says. Alerts are queued as they are listed and spoken together at the next flush, at
 * most one entry for each wording per flush, and only those the shown list holds. Each event_id is spoken at most
 * once. Old entries are trimmed to `kept` only once the page has drawn them, so no entry is removed before it was
 * spoken.
 */
export class Announcer {
  private queued: Alert[] = [];
  private readonly spoken = new Set<string>();
  private nextId = 1;
  private renderedId = 0;
  entries: LogEntry[] = [];

  constructor(private readonly kept: number) {}

  /** Alerts waiting for a flush. */
  get waiting(): boolean {
    return this.queued.length > 0;
  }

  /** Queues the alerts not yet spoken or queued; true when any was added. */
  queue(alerts: Alert[]): boolean {
    const fresh = alerts.filter(
      (a) => !this.spoken.has(a.event_id) && !this.queued.some((q) => q.event_id === a.event_id),
    );
    this.queued.push(...fresh);
    return fresh.length > 0;
  }

  /**
   * Speaks the queued alerts the list shows, in its order. Those the held list's bar holds (`waiting`, the bar's
   * event_ids, absent while the list is not held) are worded as waiting above the list, with the bar's count; the
   * rest are worded as listed. One entry for each wording, the listed ones first. With no list shown yet nothing is
   * spoken and the queue is held; alerts the list does not hold are dropped unspoken. True when an entry was added.
   */
  flush(list: AlertList | undefined, waiting?: ReadonlySet<string>): boolean {
    if (!list) {
      return false;
    }
    const queued = new Set(
      this.queued.filter((a) => !this.spoken.has(a.event_id)).map((a) => a.event_id),
    );
    this.queued = [];
    const shown = list.items.filter((a) => queued.has(a.event_id));
    const listed = shown.filter((a) => !waiting?.has(a.event_id));
    const held = shown.filter((a) => waiting?.has(a.event_id));
    if (listed.length) {
      this.add(groupAnnouncement(listed), listed);
    }
    if (held.length) {
      this.add(heldAnnouncement(held, waiting!.size), held);
    }
    return shown.length > 0;
  }

  /**
   * One summary entry for the alerts the first refresh after a pause added, `more` when it could not count them
   * all, naming what the list holds. The summary covers any of them still queued, so no flush speaks them again.
   * True when an entry was added.
   */
  paused(alerts: Alert[], more: boolean, noun: ListNoun = NOUNS.all): boolean {
    const fresh = alerts.filter((a) => !this.spoken.has(a.event_id));
    const covered = new Set(fresh.map((a) => a.event_id));
    this.queued = this.queued.filter((a) => !covered.has(a.event_id));
    return fresh.length > 0 && this.add(pausedAnnouncement(fresh.length, more, noun), fresh);
  }

  /**
   * The console is no longer shown: nothing it drew is kept, so a return draws only what is added after it. Gives
   * back the queued alerts not yet spoken, so the return can count them.
   */
  leave(): Alert[] {
    const unspoken = this.queued.filter((a) => !this.spoken.has(a.event_id));
    this.queued = [];
    this.entries = [];
    return unspoken;
  }

  /**
   * One summary entry for alerts that arrived while the console was not shown, leaving out any already spoken,
   * naming what the list holds; `more` when the count can be short. They count as spoken from then on. Returns the
   * alerts it covers.
   */
  away(alerts: Alert[], more = false, noun: ListNoun = NOUNS.all): Alert[] {
    const fresh = alerts.filter((a) => !this.spoken.has(a.event_id));
    const covered = new Set(fresh.map((a) => a.event_id));
    this.queued = this.queued.filter((a) => !covered.has(a.event_id));
    if (fresh.length) {
      this.add(awayAnnouncement(fresh.length, more, noun), fresh);
    }
    return fresh;
  }

  /** The page has drawn every entry up to this id. */
  rendered(id: number): void {
    this.renderedId = Math.max(this.renderedId, id);
  }

  private add(segments: Segment[], alerts: Alert[]): boolean {
    alerts.forEach((a) => this.spoken.add(a.event_id));
    const entries = [
      ...this.entries,
      { id: this.nextId++, segments, eventIds: alerts.map((a) => a.event_id) },
    ];
    while (entries.length > this.kept && entries[0].id <= this.renderedId) {
      entries.shift();
    }
    this.entries = entries;
    return true;
  }
}

/**
 * The alerts a refresh lists above the newest one the previous answer listed. None on the first answer. When that
 * newest one has left the page, every alert the previous answer did not list, and `more` when the page has more
 * after it, since then more may have arrived than the page shows.
 */
export function pollAdded(
  previous: { items: Alert[] } | undefined,
  next: { items: Alert[]; next: string | null },
): { alerts: Alert[]; more: boolean } {
  if (!previous) {
    return { alerts: [], more: false };
  }
  const top = previous.items[0]?.event_id;
  const at = top === undefined ? -1 : next.items.findIndex((a) => a.event_id === top);
  if (at >= 0) {
    return { alerts: next.items.slice(0, at), more: false };
  }
  const before = new Set(previous.items.map((a) => a.event_id));
  return {
    alerts: next.items.filter((a) => !before.has(a.event_id)),
    more: top !== undefined && next.next !== null,
  };
}
