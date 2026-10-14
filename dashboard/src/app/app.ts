import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  linkedSignal,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ViewportScroller } from '@angular/common';
import {
  NavigationCancel,
  NavigationEnd,
  NavigationError,
  NavigationStart,
  Router,
  RouterOutlet,
} from '@angular/router';

import { Failures } from './banners/banner-view';
import { DashboardData } from './data/dashboard-data';
import { datetimeAttr, hms, parseUtc } from './format';
import { IconSprite } from './icons/icon-sprite';
import { RouteFocus } from './route-focus';
import { SessionService } from './session/session.service';

/** The path of a URL without its query string or fragment, so a filter change is not a page change. */
export function pathOf(url: string): string {
  return url.split(/[?#]/)[0];
}

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconSprite, RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly data = inject(DashboardData);
  protected readonly session = inject(SessionService).state;
  private readonly router = inject(Router);
  private readonly path = signal(pathOf(this.router.url));

  /** The console is the page at /; its header name is the page's h1, and only it has the skip links. */
  protected readonly onConsole = computed(() => this.path() === '/');

  protected readonly spaceWeather = computed(
    () => this.data.spaceWeather.data()?.space_weather_current,
  );

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

  constructor() {
    const host: HTMLElement = inject(ElementRef).nativeElement;
    const injector = inject(Injector);
    const scroller = inject(ViewportScroller);
    const focus = inject(RouteFocus);
    let first = true;
    let fromHistory = false;
    this.router.events.pipe(takeUntilDestroyed()).subscribe((event) => {
      if (event instanceof NavigationStart) {
        fromHistory = event.navigationTrigger === 'popstate';
        focus.start(this.router.url, document.activeElement);
        return;
      }
      if (event instanceof NavigationCancel || event instanceof NavigationError) {
        focus.abandon();
        return;
      }
      if (!(event instanceof NavigationEnd)) {
        return;
      }
      const path = pathOf(event.urlAfterRedirects);
      const changed = path !== this.path() && !first;
      this.path.set(path);
      first = false;
      // A new page takes focus once it has settled (RouteFocus). History keeps its own scroll position.
      focus.end(event.urlAfterRedirects, changed, fromHistory);
      if (!changed) {
        return;
      }
      if (!fromHistory) {
        afterNextRender(() => scroller.scrollToPosition([0, 0]), { injector });
      }
      if (path === '/') {
        // The console's list and its h1 come from data the app already holds, so it has settled at once.
        focus.settled(host, injector);
      }
    });
  }

  protected togglePause(): void {
    if (this.data.paused()) {
      this.data.resume();
    } else {
      this.data.pause();
    }
  }
}
