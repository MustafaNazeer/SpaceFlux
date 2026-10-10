import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Badge } from '../badge/badge';
import { PassesData } from '../data/passes-data';
import { dateHms, hms, parseUtc } from '../format';
import { BadgeView } from '../scales';
import { commonView, objectView } from './pass-view';

const CLIPPED_BADGE: BadgeView = { classes: 'clipped', icon: 'i-clipped', text: 'Clipped' };

/** The watchlist's passes over the fixed observer, fetched on load and on the viewer's refresh only. */
@Component({
  selector: 'app-passes-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Badge],
  templateUrl: './passes-panel.html',
  styleUrl: './passes-panel.css',
})
export class PassesPanel {
  private readonly passes = inject(PassesData);
  /** Set by the Refresh button, so completion is announced for a refresh and never for the first load. */
  private readonly refreshed = signal(false);

  protected readonly loading = this.passes.loading;
  protected readonly clipped = CLIPPED_BADGE;

  protected readonly view = computed(() => {
    const objects = this.passes.data();
    return objects ? { common: commonView(objects), objects: objects.map(objectView) } : null;
  });

  /** The start of the window shown, as the server's clock read it; NaN when there is none to read. */
  private readonly shownFrom = computed(() => parseUtc(this.view()?.common?.windowStart.datetime));

  protected readonly status = computed(() => {
    const has = this.view() !== null;
    if (this.loading()) {
      return has ? '' : 'Computing passes for the watchlist.';
    }
    if (this.passes.failedAt() !== undefined) {
      if (!has) {
        return 'Passes could not be loaded. Use Refresh passes to try again.';
      }
      return Number.isNaN(this.shownFrom())
        ? 'Passes could not be refreshed. The passes shown are from the last answer that succeeded.'
        : `Passes could not be refreshed. The passes shown are for the 24 hours from ${dateHms(this.shownFrom())} UTC.`;
    }
    if (!this.refreshed() || !has) {
      return '';
    }
    return Number.isNaN(this.shownFrom())
      ? 'Passes updated.'
      : `Passes updated at ${hms(this.shownFrom())} UTC.`;
  });

  constructor() {
    this.passes.load();
  }

  protected refresh(): void {
    if (this.passes.load()) {
      this.refreshed.set(true);
    }
  }
}
