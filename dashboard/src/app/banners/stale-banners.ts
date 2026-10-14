import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  Injectable,
  input,
  linkedSignal,
} from '@angular/core';

import { Segments } from '../badge/segments';
import { Announcement, announcement, BannerView, Spoken } from './banner-view';

/** What the status line stood at when the console was last left, so a return can tell what changed meanwhile. */
@Injectable({ providedIn: 'root' })
export class StaleLineMemory {
  left: { spoken: Spoken[]; announcement: Announcement } | undefined;
}

const sameSet = (a: Spoken[], b: Spoken[]) =>
  a.length === b.length && a.every((x, i) => x.key === b[i].key && x.announce === b[i].announce);

/**
 * Freshness banners above the panels. The visible banners are not a live region, since their ages change on
 * every refresh; a separate status element speaks only when the set of stale feeds changes.
 */
@Component({
  selector: 'app-stale-banners',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Segments],
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `
    <p class="visually-hidden" role="status">{{ status() }}</p>
    <section class="banners" aria-label="Feed freshness">
      @for (b of banners(); track b.key) {
        <div class="banner">
          <svg class="icon" aria-hidden="true"><use href="#i-clock" /></svg>
          <p>
            <strong class="stale-word">{{ b.lead }}</strong><app-segments [segments]="b.body" />
          </p>
        </div>
      }
    </section>
  `,
  styles: `
    .banners {
      display: grid;
      gap: var(--space-3);
      grid-column: 1 / -1;
    }
    .banners:empty {
      margin-bottom: calc(var(--page-gap, 0px) * -1);
    }
  `,
})
export class StaleBanners {
  readonly banners = input.required<BannerView[]>();

  /** Only what each banner announces, so the status recomputes when the set of banners changes, not their ages. */
  private readonly spoken = computed(
    () => this.banners().map(({ key, announce }) => ({ key, announce })),
    { equal: sameSet },
  );

  private readonly memory = inject(StaleLineMemory);

  /**
   * The first set of banners is drawn unspoken. On a return to the console it stays unspoken, so nothing said before
   * is repeated (F32), unless it differs from the set shown when the console was left, as a change made meanwhile.
   * On the first visit in a page load nothing has been said yet, so it is spoken once a frame has been drawn (F43).
   * A later change is spoken as usual.
   */
  private readonly announcement = linkedSignal<Spoken[], Announcement>({
    source: this.spoken,
    computation: (spoken, previous) => {
      if (previous) {
        return announcement(spoken, previous.value);
      }
      const left = this.memory.left;
      if (left && !sameSet(left.spoken, spoken)) {
        return announcement(spoken, left.announcement);
      }
      return { ...announcement(spoken, left?.announcement), text: '' };
    },
  });

  constructor() {
    let frame = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    // After a frame is drawn with the line empty, so the live region exists before its text arrives, as on a direct
    // load, where the data arrives after the first render.
    afterNextRender(() => {
      frame = requestAnimationFrame(() => {
        timer = setTimeout(() => this.speakFirstVisit());
      });
    });
    inject(DestroyRef).onDestroy(() => {
      cancelAnimationFrame(frame);
      clearTimeout(timer);
      this.memory.left = { spoken: this.spoken(), announcement: this.announcement() };
    });
  }

  /** Speaks the set the line started from, unless the console was left before or a change was spoken since. */
  private speakFirstVisit(): void {
    if (this.memory.left === undefined && !this.announcement().text) {
      this.announcement.set(announcement(this.spoken(), undefined));
    }
  }

  protected readonly status = computed(() => this.announcement().text);
}
