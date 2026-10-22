import { computed, Injectable, signal } from '@angular/core';

/**
 * Which alerts of one list have a note form open or an acknowledgement request out. The recent alerts list holds
 * while any has, as it does while focus is inside it, so an item never moves under the operator.
 */
@Injectable()
export class AckActivity {
  private readonly open = signal<ReadonlySet<string>>(new Set());
  private readonly busy = signal<ReadonlySet<string>>(new Set());

  readonly active = computed(() => this.open().size > 0 || this.busy().size > 0);

  setOpen(eventId: string, on: boolean): void {
    this.open.update((s) => toggled(s, eventId, on));
  }

  setBusy(eventId: string, on: boolean): void {
    this.busy.update((s) => toggled(s, eventId, on));
  }
}

function toggled(set: ReadonlySet<string>, id: string, on: boolean): ReadonlySet<string> {
  if (set.has(id) === on) {
    return set;
  }
  const next = new Set(set);
  if (on) {
    next.add(id);
  } else {
    next.delete(id);
  }
  return next;
}
