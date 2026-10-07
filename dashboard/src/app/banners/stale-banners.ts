import { ChangeDetectionStrategy, Component, computed, input, linkedSignal } from '@angular/core';

import { Segments } from '../badge/segments';
import { Announcement, announcement, BannerView, Spoken } from './banner-view';

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
    {
      equal: (a, b) =>
        a.length === b.length &&
        a.every((x, i) => x.key === b[i].key && x.announce === b[i].announce),
    },
  );

  private readonly announcement = linkedSignal<Spoken[], Announcement>({
    source: this.spoken,
    computation: (spoken, previous) => announcement(spoken, previous?.value),
  });

  protected readonly status = computed(() => this.announcement().text);
}
