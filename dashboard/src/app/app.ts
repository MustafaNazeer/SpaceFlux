import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal } from '@angular/core';

import { RecentAlerts } from './alerts/recent-alerts';
import {
  Failures,
  feedBanners,
  pausedBanner,
  refreshBanner,
  screeningBanner,
} from './banners/banner-view';
import { StaleBanners } from './banners/stale-banners';
import { Clock } from './data/clock';
import { DashboardData } from './data/dashboard-data';
import { datetimeAttr, hms, parseUtc } from './format';
import { IconSprite } from './icons/icon-sprite';
import { PassesPanel } from './passes/passes-panel';
import { Screening } from './screening/screening';
import { SessionService } from './session/session.service';
import { SpaceWeather } from './space-weather/space-weather';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconSprite, StaleBanners, SpaceWeather, Screening, PassesPanel, RecentAlerts],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly data = inject(DashboardData);
  protected readonly session = inject(SessionService).state;
  protected readonly now = inject(Clock).now;

  protected readonly spaceWeather = computed(
    () => this.data.spaceWeather.data()?.space_weather_current,
  );
  protected readonly screening = computed(() => this.data.screening.data()?.screening_current);
  protected readonly alerts = computed(() => this.data.alerts.data()?.alerts);

  /** Browser time of each query's newest failed refresh. */
  private readonly failures = computed<Failures>(() => ({
    spaceWeather: this.data.spaceWeather.failedAt(),
    screening: this.data.screening.failedAt(),
    alerts: this.data.alerts.failedAt(),
  }));

  /**
   * The live label tracks the API's own clock on the newest space weather answer. The dot pulses once, when
   * the first answer arrives, and never on later refreshes (WCAG 2.2.2).
   */
  protected readonly live = computed(() => {
    const asOf = parseUtc(this.spaceWeather()?.as_of);
    const has = !Number.isNaN(asOf);
    let state: 'connecting' | 'live' | 'stale' | 'paused' = 'connecting';
    if (this.data.paused()) {
      state = 'paused';
    } else if (Object.values(this.failures()).some((t) => t !== undefined)) {
      state = 'stale';
    } else if (has) {
      state = 'live';
    }
    return {
      state,
      datetime: has ? datetimeAttr(asOf) : null,
      time: has ? `${hms(asOf)} UTC` : '',
    };
  });

  protected readonly label: Record<string, string> = {
    connecting: 'Connecting',
    live: 'Live',
    stale: 'Stale',
    paused: 'Paused',
  };

  /** Set once, at the first live answer, so the pulse animation is applied exactly one time. */
  protected readonly pulsed = linkedSignal<boolean, boolean>({
    source: () => this.live().state === 'live',
    computation: (isLive, previous) => (previous?.value ?? false) || isLive,
  });

  protected togglePause(): void {
    if (this.data.paused()) {
      this.data.resume();
    } else {
      this.data.pause();
    }
  }

  /** Paused first, else a failed refresh first, then the stale feeds and the stale screening run. */
  protected readonly banners = computed(() => {
    const asOf = this.spaceWeather()?.as_of;
    const lead = this.data.paused()
      ? pausedBanner(true, asOf, this.failures())
      : refreshBanner(this.failures(), asOf);
    const run = screeningBanner(this.screening(), this.now());
    return [...(lead ? [lead] : []), ...feedBanners(this.spaceWeather()), ...(run ? [run] : [])];
  });
}
