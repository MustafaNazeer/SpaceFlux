import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  linkedSignal,
  signal,
  untracked,
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
import { SignInForm } from './session/sign-in-form';
import { noticeParts, SessionLimits } from './session/session-limits';
import { Segments } from './badge/segments';

/** The path of a URL without its query string or fragment, so a filter change is not a page change. */
export function pathOf(url: string): string {
  return url.split(/[?#]/)[0];
}

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconSprite, RouterOutlet, Segments, SignInForm],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly data = inject(DashboardData);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  protected readonly sessions = inject(SessionService);
  protected readonly limits = inject(SessionLimits);
  /** The session time limit notice with its sentence, and whether Stay signed in can help. */
  protected readonly notice = computed(() => {
    const n = this.limits.notice();
    return n ? { parts: noticeParts(n), stay: n.kind === 'idle' } : null;
  });
  /** The form stays open while the sign in state is unknown, so its line can say so. */
  protected readonly formShown = computed(
    () =>
      this.formOpen() && this.session().kind !== 'signed_in' && this.session().kind !== 'checking',
  );
  protected readonly session = this.sessions.state;
  protected readonly line = this.sessions.line;
  /** The sign in form is open in the header in place of the Sign in button. */
  protected readonly formOpen = signal(false);
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
    const host = this.host;
    const injector = this.injector;
    const scroller = inject(ViewportScroller);
    const focus = inject(RouteFocus);
    this.followSessionFocus();
    this.keepScreenStillForNotice();
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

  protected signOut(): void {
    void this.sessions.signOut();
  }

  /** Cancel or Escape returns focus to Sign in; after a sign in the session moves it to Sign out. */
  protected formClosed(reason: 'cancel' | 'success'): void {
    this.formOpen.set(false);
    if (reason === 'cancel') {
      afterNextRender(() => this.sessionTarget('signin')?.focus(), { injector: this.injector });
    }
  }

  protected stay(): void {
    void this.limits.stay();
  }

  private sessionTarget(which: 'signin' | 'signout' | 'who'): HTMLElement | null {
    return this.host.querySelector<HTMLElement>(`.session .${which}`);
  }

  /**
   * Moves focus where the session asks: to Sign out after a sign in, to Sign in after a sign out or an ended session,
   * to the "Signed in as" line after Stay signed in. An ended session found by a background check moves focus only if
   * focus was lost with the removed controls, and never out of a note kept in place.
   */
  private followSessionFocus(): void {
    effect(() => {
      const request = this.sessions.focus();
      if (request === null) {
        return;
      }
      untracked(() => {
        if (this.session().kind === 'signed_in') {
          this.formOpen.set(false);
        }
        afterNextRender(
          () => {
            const active = document.activeElement;
            if (active instanceof HTMLElement && active.matches('.kept-note textarea')) {
              return;
            }
            if (request.onlyIfLost && active && active !== document.body) {
              return;
            }
            const target =
              request.target === 'sign_in'
                ? 'signin'
                : request.target === 'sign_out'
                  ? 'signout'
                  : 'who';
            this.sessionTarget(target)?.focus();
          },
          { injector: this.injector },
        );
      });
    });
  }

  /**
   * The session notice is the header's last row. When it appears with the header above the visible part of the
   * page, what is on screen should stay where it was. The browser's scroll anchoring may already keep it there, in
   * full or in part, so the page measures an element below the header before and after and scrolls only by what is
   * left of the shift.
   */
  private keepScreenStillForNotice(): void {
    let shown = false;
    effect(() => {
      const now = this.notice() !== null;
      untracked(() => {
        const appeared = now && !shown;
        shown = now;
        const header = this.host.querySelector<HTMLElement>('.app-header');
        const anchor = this.host.querySelector<HTMLElement>('main');
        if (!appeared || !header || !anchor || header.getBoundingClientRect().bottom > 0) {
          return;
        }
        const before = anchor.getBoundingClientRect().top;
        afterNextRender(
          () => {
            const moved = anchor.getBoundingClientRect().top - before;
            if (moved !== 0) {
              window.scrollBy({ top: moved, left: 0, behavior: 'instant' });
            }
          },
          { injector: this.injector },
        );
      });
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
