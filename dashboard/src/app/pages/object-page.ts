import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Apollo } from 'apollo-angular';
import { Subscription } from 'rxjs';

import { AlertItem } from '../alerts/alert-item';
import { focusAfterOlder, ListEnd, NOUNS, OLDER_IDLE, OlderState } from '../alerts/list-end';
import { Clock } from '../data/clock';
import { Alert, OBJECT_ALERTS, OBJECT_ALERTS_PAGE, ObjectAlertsData } from '../data/queries';
import { at, dateHms, objectName, parseUtc, Segment, text } from '../format';
import { APPROACH_NOTE, DetailFact } from './alert-detail-view';
import { RouteFocus } from '../route-focus';
import { DetailFacts } from './detail-facts';
import { LoadPhase, refusedInput } from './load';

/** The largest catalog number the API takes (docs/api/rest.md, section 5). */
export const NORAD_MAX = 999_999_999;

/** The catalog number in an address, or null when it is not a whole number from 0 to 999999999. */
export function catalogNumber(segment: string | null): number | null {
  if (segment === null || !/^\d+$/.test(segment)) {
    return null;
  }
  const n = Number(segment);
  return n <= NORAD_MAX ? n : null;
}

type Catalog = NonNullable<ObjectAlertsData['catalog_object']>;

type ObjectState =
  | { phase: LoadPhase; result?: undefined }
  | { phase: 'done'; result: 'found'; object: Catalog; items: Alert[]; next: string | null }
  | { phase: 'done'; result: 'no_entry' | 'refused' };

function time(iso: string | null): Segment | null {
  const ms = parseUtc(iso);
  return Number.isNaN(ms) ? (iso === null ? null : text(iso)) : at(ms, dateHms, ' UTC');
}

/**
 * One object's close approaches at /objects/{norad_cat_id}/alerts, in either role, newest first. It loads once on
 * entry; Load older adds 20 at a time below. A missing catalog row means the API cannot list the number's close
 * approaches (the row can be missing while close approaches are stored), so it never reads as no alerts.
 */
@Component({
  selector: 'app-object-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertItem, DetailFacts, ListEnd, RouterLink],
  templateUrl: './object-page.html',
})
export class ObjectPage {
  private readonly apollo = inject(Apollo);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly routeFocus = inject(RouteFocus);
  protected readonly now = inject(Clock).now;
  private request: Subscription | undefined;
  private olderRequest: Subscription | undefined;

  protected readonly number = signal<number | null>(null);
  protected readonly state = signal<ObjectState>({ phase: 'loading' });
  protected readonly older = signal<OlderState>(OLDER_IDLE);
  protected readonly noun = NOUNS.close_approach;
  protected readonly note = APPROACH_NOTE;

  protected readonly found = computed(() => {
    const s = this.state();
    return s.result === 'found' ? s : null;
  });

  /** The object's name, or "object {number}" when the catalog has none, as it is only used mid sentence. */
  protected readonly name = computed(() => {
    const o = this.found()?.object;
    return objectName(o?.object_name ?? null, this.number(), { mid: true });
  });

  protected readonly heading = computed(() => {
    const s = this.state();
    if (s.result === 'refused') {
      return 'Not a catalog number';
    }
    if (s.result === 'no_entry') {
      return `No catalog entry for object ${this.number()}`;
    }
    return s.result === 'found' ? `Close approaches of ${this.name()}` : `Object ${this.number()}`;
  });

  protected readonly status = computed(() => {
    switch (this.state().phase) {
      case 'loading':
        return `Loading object ${this.number()}.`;
      case 'failed':
        return `Object ${this.number()} could not be loaded.`;
      default:
        return '';
    }
  });

  protected readonly catalogFacts = computed<DetailFact[]>(() => {
    const o = this.found()?.object;
    if (!o) {
      return [];
    }
    const facts: DetailFact[] = [];
    if (o.object_name !== null) {
      facts.push({ term: 'Name', value: [text(o.object_name)], subs: [] });
    }
    if (o.object_id !== null) {
      facts.push({
        term: 'International designator',
        value: [text(o.object_id)],
        subs: [],
        mono: true,
      });
    }
    const epoch = time(o.epoch);
    const fetched = time(o.last_fetched_at);
    if (epoch) {
      facts.push({
        term: 'Newest element set',
        value: [text('Epoch '), epoch],
        subs: fetched ? [{ segments: [text('Last fetched '), fetched] }] : [],
      });
    }
    return facts;
  });

  constructor() {
    const title = inject(Title);
    effect(() => {
      const s = this.state();
      const named =
        s.result === 'found'
          ? `Close approaches of ${objectName(s.object.object_name, this.number(), { mid: true, numbered: true })}`
          : this.heading();
      title.setTitle(`${named}, SpaceFlux`);
    });
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        this.number.set(catalogNumber(params.get('noradCatId')));
        this.load('loading');
      });
  }

  protected tryAgain(): void {
    if (this.state().phase !== 'retry') {
      this.load('retry');
    }
  }

  /** The page after the oldest close approach shown; sends nothing while one is out. */
  protected loadOlder(): void {
    const s = this.found();
    const n = this.number();
    if (!s || n === null || s.next === null || this.older().phase === 'loading') {
      return;
    }
    const seq = this.older().seq + 1;
    this.older.set({ ...OLDER_IDLE, phase: 'loading', seq });
    this.olderRequest = this.apollo
      .query({
        query: OBJECT_ALERTS,
        variables: { norad_cat_id: n, limit: OBJECT_ALERTS_PAGE, after: s.next },
        fetchPolicy: 'no-cache',
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          const page = result.data?.catalog_object?.close_approaches;
          const current = this.found();
          if (!page || !current) {
            this.older.set({ ...OLDER_IDLE, phase: 'failed', seq });
            return;
          }
          const known = new Set(current.items.map((a) => a.event_id));
          const added = page.items.filter((a) => !known.has(a.event_id));
          this.state.set({ ...current, items: [...current.items, ...added], next: page.next });
          this.older.set({
            phase: 'loaded',
            added: added.length,
            firstAdded: added[0]?.event_id ?? null,
            end: page.next === null,
            seq,
          });
          if (page.next === null) {
            const state = this.older();
            afterNextRender(() => focusAfterOlder(this.host, state), { injector: this.injector });
          }
        },
        error: () => this.older.set({ ...OLDER_IDLE, phase: 'failed', seq }),
      });
  }

  private load(phase: 'loading' | 'retry'): void {
    this.request?.unsubscribe();
    this.olderRequest?.unsubscribe();
    this.older.set(OLDER_IDLE);
    const n = this.number();
    if (n === null) {
      this.state.set({ phase: 'done', result: 'refused' });
      this.afterLoad(phase);
      return;
    }
    this.state.set({ phase });
    this.request = this.apollo
      .query({
        query: OBJECT_ALERTS,
        variables: { norad_cat_id: n, limit: OBJECT_ALERTS_PAGE },
        fetchPolicy: 'no-cache',
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          const object = result.data?.catalog_object;
          if (result.data === undefined) {
            this.state.set({ phase: 'failed' });
          } else if (object) {
            const page = object.close_approaches;
            this.state.set({
              phase: 'done',
              result: 'found',
              object,
              items: page.items,
              next: page.next,
            });
          } else {
            this.state.set({ phase: 'done', result: 'no_entry' });
          }
          this.afterLoad(phase);
        },
        error: (error: unknown) => {
          this.state.set(
            refusedInput(error) ? { phase: 'done', result: 'refused' } : { phase: 'failed' },
          );
          this.afterLoad(phase);
        },
      });
  }

  /**
   * The first load has answered, failed, or was not needed: a page change waiting for it moves focus now (F29).
   * Try again is removed once its request answers, so focus goes to the page title rather than to the body.
   */
  private afterLoad(phase: 'loading' | 'retry'): void {
    if (phase === 'loading') {
      this.routeFocus.settled(this.host, this.injector);
    } else if (this.state().phase === 'done') {
      afterNextRender(() => this.host.querySelector<HTMLElement>('h1')?.focus(), {
        injector: this.injector,
      });
    }
  }
}
