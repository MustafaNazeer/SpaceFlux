import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';

import { RecentAlerts } from '../alerts/recent-alerts';
import {
  Failures,
  feedBanners,
  pausedBanner,
  refreshBanner,
  screeningBanner,
} from '../banners/banner-view';
import { StaleBanners } from '../banners/stale-banners';
import { Clock } from '../data/clock';
import { DashboardData } from '../data/dashboard-data';
import { AlertKind, kindOf } from '../data/queries';
import { PassesPanel } from '../passes/passes-panel';
import { Screening } from '../screening/screening';
import { SpaceWeather } from '../space-weather/space-weather';

export const CONSOLE_TITLE = 'SpaceFlux operator console';

/**
 * The operator console at /. The recent alerts filter lives in the query string (/?kind=...), so going back to the
 * console restores it; every entry, and every filter change, shows the first page.
 */
@Component({
  selector: 'app-console',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StaleBanners, SpaceWeather, Screening, PassesPanel, RecentAlerts],
  templateUrl: './console-page.html',
  styleUrl: './console-page.css',
  host: { style: 'display: contents' },
})
export class ConsolePage {
  protected readonly data = inject(DashboardData);
  protected readonly now = inject(Clock).now;
  private readonly router = inject(Router);

  protected readonly spaceWeather = computed(
    () => this.data.spaceWeather.data()?.space_weather_current,
  );
  protected readonly screening = computed(() => this.data.screening.data()?.screening_current);

  private readonly failures = computed<Failures>(() => ({
    spaceWeather: this.data.spaceWeather.failedAt(),
    screening: this.data.screening.failedAt(),
    alerts: this.data.alerts.failedAt(),
  }));

  /** Paused first, else a failed refresh first, then the stale feeds and the stale screening run. */
  protected readonly banners = computed(() => {
    const asOf = this.spaceWeather()?.as_of;
    const lead = this.data.paused()
      ? pausedBanner(true, asOf, this.failures())
      : refreshBanner(this.failures(), asOf);
    const run = screeningBanner(this.screening(), this.now());
    return [...(lead ? [lead] : []), ...feedBanners(this.spaceWeather()), ...(run ? [run] : [])];
  });

  constructor() {
    inject(Title).setTitle(CONSOLE_TITLE);
    inject(ActivatedRoute)
      .queryParamMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        const kind = kindOf(params.get('kind'));
        if (kind === undefined) {
          // Not a kind the API knows: show every kind, and say so in the address.
          this.setKind(null);
          return;
        }
        this.data.showKind(kind);
      });
    // The log region is in the page from the first render, so a summary added after it is spoken.
    afterNextRender(() => this.data.enterConsole());
    inject(DestroyRef).onDestroy(() => this.data.leaveConsole());
  }

  protected setKind(kind: AlertKind | null): void {
    // Replaced rather than pushed, so Back leaves the console instead of stepping through filters.
    this.router.navigate([], { queryParams: { kind }, replaceUrl: true });
  }
}
