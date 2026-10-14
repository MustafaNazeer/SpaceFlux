import { signal } from '@angular/core';

import { Alert } from '../data/queries';

/** How long an alert that joined the list while the page was open stays marked New. */
export const NEW_FOR_MS = 10 * 60_000;

/**
 * Which shown alerts are New: those that joined the list above the newest alert it already held while it was on
 * screen (every shown alert, once none it held is left), and those seeded as having arrived while the list was not
 * shown. The first list seen, a list for another filter, and alerts added below (older pages) are never New of
 * themselves. A mark clears NEW_FOR_MS after the alert joined, or after
 * it was seeded, and all marks go with the component that holds them.
 */
export class NewMarks {
  private previous: { ids: Set<string> } | null = null;
  /** The filter of the newest list seen; a list for another one drops every mark. */
  private key: string | undefined;
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly state = signal<ReadonlySet<string>>(new Set());

  readonly ids = this.state.asReadonly();

  /** Takes the list now shown; `key` names the filter, so a list for another one starts again unmarked. */
  observe(items: readonly Alert[] | undefined, key: string): void {
    if (this.key !== undefined && this.key !== key) {
      this.clear();
      this.previous = null;
    }
    this.key = key;
    if (!items) {
      this.previous = null;
      return;
    }
    const previous = this.previous;
    this.previous = { ids: new Set(items.map((a) => a.event_id)) };
    if (!previous) {
      return;
    }
    // Above the newest alert the previous list held that is still listed; every shown alert once none is (F41).
    const at = items.findIndex((a) => previous.ids.has(a.event_id));
    const above = at < 0 ? items : items.slice(0, at);
    this.mark(above.map((a) => a.event_id));
  }

  /** Marks alerts that arrived while the list was not shown, for NEW_FOR_MS from now. */
  seed(eventIds: Iterable<string>): void {
    this.mark([...eventIds]);
  }

  destroy(): void {
    this.clear();
    this.previous = null;
    this.key = undefined;
  }

  private mark(eventIds: string[]): void {
    if (!eventIds.length) {
      return;
    }
    const next = new Set(this.state());
    for (const id of eventIds) {
      next.add(id);
      clearTimeout(this.timers.get(id));
      this.timers.set(
        id,
        setTimeout(() => this.expire(id), NEW_FOR_MS),
      );
    }
    this.state.set(next);
  }

  private expire(eventId: string): void {
    this.timers.delete(eventId);
    const next = new Set(this.state());
    next.delete(eventId);
    this.state.set(next);
  }

  private clear(): void {
    this.timers.forEach((t) => clearTimeout(t));
    this.timers.clear();
    if (this.state().size) {
      this.state.set(new Set());
    }
  }
}
