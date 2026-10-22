import { Injectable, signal } from '@angular/core';

/**
 * Notes that were being written when the session ended on its own, by event_id. They stay in place, read only, and
 * are never sent by themselves; after the next sign in each becomes its note form again. They live only in this tab.
 */
@Injectable({ providedIn: 'root' })
export class KeptNotes {
  private readonly notes = signal<ReadonlyMap<string, string>>(new Map());

  readonly size = () => this.notes().size;

  get(eventId: string): string | undefined {
    return this.notes().get(eventId);
  }

  set(eventId: string, text: string): void {
    this.notes.update((m) => new Map(m).set(eventId, text));
  }

  delete(eventId: string): void {
    if (this.notes().has(eventId)) {
      this.notes.update((m) => {
        const next = new Map(m);
        next.delete(eventId);
        return next;
      });
    }
  }

  clear(): void {
    this.notes.set(new Map());
  }
}
