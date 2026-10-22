import {
  afterNextRender,
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { Segments } from '../badge/segments';
import { Alert, AlertKind } from '../data/queries';
import { AlertItem, newBorder } from './alert-item';
import { AlertList } from './alert-list';
import { LogEntry } from './announcer';
import { holdList } from './held-list';
import { endLine, focusAfterOlder, ListEnd, NOUNS, OLDER_IDLE, OlderState } from './list-end';
import { NewMarks } from './new-marks';

export interface KindOption {
  value: '' | AlertKind;
  label: string;
}

export const KIND_OPTIONS: readonly KindOption[] = [
  { value: '', label: 'All' },
  { value: 'close_approach', label: 'Close approaches' },
  { value: 'space_weather_level', label: 'Space weather' },
];

/** What the panel's status line calls the list under each filter, and the lead of its empty state. */
const LIST_NAMES: Record<'' | AlertKind, { loading: string; failed: string; empty: string }> = {
  '': { loading: 'recent alerts', failed: 'Recent alerts', empty: 'No alerts are stored yet.' },
  close_approach: {
    loading: 'close approaches',
    failed: 'Close approaches',
    empty: 'No close approaches are stored yet.',
  },
  space_weather_level: {
    loading: 'space weather alerts',
    failed: 'Space weather alerts',
    empty: 'No space weather alerts are stored yet.',
  },
};

/**
 * The status line once the first answer for a new filter is drawn: the end line, the count with a pointer to
 * Load older while older alerts exist, or the empty state's lead.
 */
export function filterResult(count: number, more: boolean, kind: AlertKind | null): string {
  if (count === 0) {
    return LIST_NAMES[kind ?? ''].empty;
  }
  const noun = NOUNS[kind ?? 'all'];
  if (more) {
    return `${count} ${count === 1 ? noun.one : noun.many} shown, newest first. Older ones can be loaded.`;
  }
  return endLine(count, false, noun);
}

/**
 * Recent alerts, newest first, with their acknowledgement state, a filter by kind and Load older. Read only. While
 * focus is inside the list (or in the bar that offers what is waiting) or a form is open, the list is held: it never
 * trims or reorders, and new alerts wait in a bar above it.
 */
@Component({
  selector: 'app-recent-alerts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertItem, ListEnd, Segments],
  templateUrl: './recent-alerts.html',
  styleUrl: './recent-alerts.css',
  host: { style: 'display: contents' },
})
export class RecentAlerts {
  readonly page = input.required<AlertList | undefined>();
  /** The filter the page is for; null is every kind. */
  readonly kind = input<AlertKind | null>(null);
  /** The live log's entries, oldest first; each is spoken once when it is added. */
  readonly log = input<LogEntry[]>([]);
  /** The id of the newest log entry drawn, so the data layer trims only entries already spoken. */
  readonly logRendered = output<number>();
  readonly now = input.required<number>();
  /** Browser time of the newest failed fetch, so a first fetch that fails does not read as loading forever. */
  readonly failedAt = input<number | undefined>(undefined);
  /** While updates are paused no refresh is sent, so a failed load waits for the viewer to resume. */
  readonly paused = input(false);
  readonly older = input<OlderState>(OLDER_IDLE);
  /** True while an item's form is open, which holds the list like focus inside it does. */
  readonly formOpen = input(false);
  /** Alerts that arrived while the console was not shown; they are marked New for NEW_FOR_MS. */
  readonly arrived = input<ReadonlySet<string>>(new Set());
  readonly kindChange = output<AlertKind | null>();
  readonly loadOlder = output<void>();
  /**
   * The alerts shown when the hold began, or null once the list is no longer held, so what the log says about new
   * alerts matches the bar above the list.
   */
  readonly heldChange = output<readonly Alert[] | null>();

  protected readonly options = KIND_OPTIONS;
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  private readonly marks = new NewMarks();
  /** The alerts shown when the hold began, and the filter they were for; null while not held. */
  private readonly frozen = signal<{ kind: AlertKind | null; items: Alert[] } | null>(null);
  private focusInside = false;
  private focusTimer: ReturnType<typeof setTimeout> | undefined;
  /** The result of the newest filter change, spoken once its first answer is drawn; empty otherwise. */
  private readonly result = signal('');

  protected readonly selected = computed(() => this.kind() ?? '');

  /** Text of the persistent status region: loading, failed, or empty once the list is shown. */
  protected readonly status = computed(() => {
    if (this.page()) {
      return this.result();
    }
    const name = LIST_NAMES[this.selected()];
    return this.failedAt() === undefined
      ? `Loading ${name.loading}.`
      : `${name.failed} could not be loaded. The page tries again ${this.paused() ? 'when updates resume' : 'at the next refresh'}.`;
  });

  /** The list as drawn, with what waits while it is held. */
  protected readonly drawn = computed(() => {
    const page = this.page();
    if (!page) {
      return undefined;
    }
    const frozen = this.frozen();
    if (!frozen || frozen.kind !== this.kind()) {
      return { items: page.items, held: [] as Alert[], more: page.more };
    }
    return { ...holdList(frozen.items, page.items), more: page.more };
  });

  protected readonly noun = computed(() => NOUNS[this.kind() ?? 'all']);
  protected readonly newIds = this.marks.ids;

  protected readonly views = computed(() =>
    (this.drawn()?.items ?? []).map((a) => ({ alert: a, border: newBorder(a) })),
  );

  /** The alerts the hold began with, or null while the list is not held. */
  private readonly held = computed(() => {
    const frozen = this.frozen();
    return frozen !== null && frozen.kind === this.kind() ? frozen.items : null;
  });

  private readonly barShown = computed(() => (this.drawn()?.held.length ?? 0) > 0);

  constructor() {
    afterRenderEffect(() => {
      const newest = this.log().at(-1);
      if (newest) {
        this.logRendered.emit(newest.id);
      }
    });
    effect(() => {
      const items = this.drawn()?.items;
      const key = this.selected();
      untracked(() => this.marks.observe(items, key));
    });
    effect(() => {
      const arrived = this.arrived();
      untracked(() => this.marks.seed(arrived));
    });
    effect(() => {
      const held = this.held();
      untracked(() => this.heldChange.emit(held));
    });
    this.watchFilterResult();
    this.keepFocusInPlace();
    effect(() => {
      if (this.formOpen()) {
        untracked(() => this.hold());
      } else if (!untracked(() => this.focusInside)) {
        this.frozen.set(null);
      }
    });
    let seen: OlderState | undefined;
    effect(() => {
      const state = this.older();
      if (seen !== undefined && state !== seen && state.phase === 'loaded') {
        afterNextRender(() => focusAfterOlder(this.host, state), { injector: this.injector });
      }
      seen = state;
    });
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.focusTimer);
      this.marks.destroy();
    });
  }

  protected pick(value: '' | AlertKind): void {
    this.kindChange.emit(value === '' ? null : value);
  }

  /** A Load older press empties the filter result, so a later one is spoken again. */
  protected pressOlder(): void {
    this.result.set('');
    this.loadOlder.emit();
  }

  protected focusIn(): void {
    this.focusInside = true;
    this.hold();
  }

  /** Focus left an element in the held region; the hold ends once document.activeElement is outside it. */
  protected focusOut(): void {
    clearTimeout(this.focusTimer);
    this.focusTimer = setTimeout(() => {
      this.focusTimer = undefined;
      const active = document.activeElement;
      const inside = Array.from(this.host.querySelectorAll('.alert-list, .held-bar')).some((el) =>
        el.contains(active),
      );
      this.focusInside = inside;
      if (!inside && !this.formOpen()) {
        this.frozen.set(null);
      }
    });
  }

  /** Adds the waiting alerts at once and moves focus to the first one's title link. */
  protected showHeld(): void {
    const first = this.drawn()?.held[0]?.event_id;
    this.frozen.set(null);
    afterNextRender(
      () => {
        const item = Array.from(this.host.querySelectorAll<HTMLElement>('[data-event-id]')).find(
          (el) => el.dataset['eventId'] === first,
        );
        item?.querySelector<HTMLElement>('a.title-link')?.focus();
      },
      { injector: this.injector },
    );
  }

  /**
   * After a filter change, waits for the first answer under the new filter and puts its result in the status line.
   * The first list the panel shows is not a filter change and says nothing. The result is emptied, silently, the
   * next time the drawn list changes, so it never stays behind as a count that is no longer true.
   */
  private watchFilterResult(): void {
    let lastKind: AlertKind | null | undefined;
    let waiting = false;
    let drawnKey: string | null = null;
    effect(() => {
      const kind = this.kind();
      const drawn = this.drawn();
      untracked(() => {
        if (lastKind !== undefined && kind !== lastKind) {
          waiting = true;
          this.result.set('');
        }
        lastKind = kind;
        const key = drawn ? `${drawn.more} ${drawn.items.map((a) => a.event_id).join(' ')}` : null;
        if (waiting && drawn) {
          waiting = false;
          this.result.set(filterResult(drawn.items.length, drawn.more, kind));
        } else if (key !== drawnKey) {
          this.result.set('');
        }
        drawnKey = key;
      });
    });
  }

  /**
   * The held bar is drawn above the list, so when it appears with focus in the list the window scrolls by what the
   * bar added, and the focused item stays where it was on screen. Measured before the bar is drawn (effects
   * run ahead of the view they belong to) and again after.
   */
  private keepFocusInPlace(): void {
    let shown = false;
    effect(() => {
      const now = this.barShown();
      untracked(() => {
        const appeared = now && !shown;
        shown = now;
        const active = document.activeElement;
        const list = this.host.querySelector('.alert-list');
        if (!appeared || !(active instanceof HTMLElement) || !list?.contains(active)) {
          return;
        }
        const before = active.getBoundingClientRect().top;
        afterNextRender(
          () => {
            const moved = active.getBoundingClientRect().top - before;
            if (moved !== 0) {
              window.scrollBy({ top: moved, left: 0, behavior: 'instant' });
            }
          },
          { injector: this.injector },
        );
      });
    });
  }

  private hold(): void {
    if (this.frozen() === null) {
      const items = this.drawn()?.items;
      if (items) {
        this.frozen.set({ kind: this.kind(), items });
      }
    }
  }
}
