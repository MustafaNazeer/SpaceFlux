import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/** Where a Load older request stands. `seq` grows with each press, so a result can be told from the one before. */
export interface OlderState {
  phase: 'idle' | 'loading' | 'loaded' | 'failed';
  /** Alerts the last loaded press added below the ones shown before it. */
  added: number;
  /** The first alert that press added, or null when it added none. */
  firstAdded: string | null;
  /** The last loaded press reached the oldest stored alert. */
  end: boolean;
  seq: number;
}

export const OLDER_IDLE: OlderState = {
  phase: 'idle',
  added: 0,
  firstAdded: null,
  end: false,
  seq: 0,
};

/** The words for what a list holds, as its end line names it. */
export interface ListNoun {
  one: string;
  many: string;
  /** False for a list whose end line says only that every row is shown, not that every stored one is. */
  stored?: boolean;
}

export const NOUNS: Record<'all' | 'close_approach' | 'space_weather_level', ListNoun> = {
  all: { one: 'alert', many: 'alerts' },
  close_approach: { one: 'close approach', many: 'close approaches' },
  space_weather_level: { one: 'space weather alert', many: 'space weather alerts' },
};

/** "20 shown, newest first" while older alerts exist, else what the whole stored list is. */
export function endLine(count: number, more: boolean, noun: ListNoun): string {
  if (more) {
    return `${count} shown, newest first`;
  }
  const stored = noun.stored === false ? '' : 'stored ';
  return count === 1
    ? `The one ${stored}${noun.one} is shown.`
    : `All ${count} ${stored}${noun.many} are shown, newest first.`;
}

/** The persistent status line under the end line, naming what the list holds. */
export function olderStatus(state: OlderState, noun: ListNoun): string {
  switch (state.phase) {
    case 'loading':
      return `Loading older ${noun.many}.`;
    case 'failed':
      return `Older ${noun.many} could not be loaded. Use Load older to try again.`;
    case 'loaded': {
      const end = state.end ? `No older ${noun.many} are stored.` : '';
      if (state.added === 0) {
        return end;
      }
      const loaded = `${state.added} older ${state.added === 1 ? noun.one : noun.many} loaded.`;
      return end ? `${loaded} ${end}` : loaded;
    }
    default:
      return '';
  }
}

/**
 * The end of a paged alert list: the count, Load older while older alerts exist, and a persistent status line. A
 * press while a request is out sends nothing. When a press reaches the end, focus moves to the title link of the
 * first alert it added, or to the end line when it added none (see focusAfterOlder).
 */
@Component({
  selector: 'app-list-end',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `<div class="list-end">
    @if (more()) {
      <div class="list-end-row">
        <span class="caption">{{ line() }}</span>
        <button class="btn btn-secondary" type="button" [attr.aria-disabled]="loading() ? 'true' : null" (click)="press()">Load older</button>
      </div>
    } @else {
      <p class="caption list-end-line" tabindex="-1">{{ line() }}</p>
    }
    <p class="secondary" role="status">{{ status() }}</p>
  </div>`,
})
export class ListEnd {
  readonly count = input.required<number>();
  readonly more = input.required<boolean>();
  readonly noun = input.required<ListNoun>();
  readonly state = input.required<OlderState>();
  readonly loadOlder = output<void>();

  protected readonly line = computed(() => endLine(this.count(), this.more(), this.noun()));
  protected readonly status = computed(() => olderStatus(this.state(), this.noun()));
  protected readonly loading = computed(() => this.state().phase === 'loading');

  protected press(): void {
    if (!this.loading()) {
      this.loadOlder.emit();
    }
  }
}

/**
 * After a press reached the end, moves focus to the title link of the first alert it added, or to the end line
 * when it added none. `root` holds both the list and its end.
 */
export function focusAfterOlder(root: HTMLElement, state: OlderState): void {
  if (state.phase !== 'loaded' || !state.end) {
    return;
  }
  const item = Array.from(root.querySelectorAll<HTMLElement>('[data-event-id]')).find(
    (el) => el.dataset['eventId'] === state.firstAdded,
  );
  const target =
    item?.querySelector<HTMLElement>('a.title-link') ??
    root.querySelector<HTMLElement>('.list-end-line');
  target?.focus();
}
