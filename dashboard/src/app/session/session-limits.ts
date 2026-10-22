import { computed, DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';

import { Clock } from '../data/clock';
import { at, hm, Segment, text } from '../format';
import { SessionActivity } from './session-activity';
import { SessionExpiry, SessionService } from './session.service';

/** The API ends a session after 30 minutes with no request (docs/api/rest.md, section 7). */
export const IDLE_LIMIT_MS = 30 * 60_000;
/** The idle notice comes this long before the idle limit. */
export const IDLE_WARNING_MS = 2 * 60_000;
/** The 8 hour notice comes this long before the session's end. */
export const END_WARNING_MS = 10 * 60_000;
/** A limit that passed with no answered request is checked once, this long after the limit itself. */
export const LIMIT_CHECK_DELAY_MS = 60_000;

export type SessionNotice =
  { kind: 'idle'; endsAt: number; failed: boolean } | { kind: 'end'; endsAt: number };

interface Limits {
  idleEnd: number | null;
  end: SessionExpiry | null;
}

/** A time rounded down to the minute, so the notice never names a time later than the real limit. */
const minute = (ms: number) => Math.floor(ms / 60_000) * 60_000;

/** The notice's bold lead and the rest of its sentence. */
export function noticeParts(notice: SessionNotice): { lead: Segment[]; rest: Segment[] } {
  const time = at(minute(notice.endsAt), hm, ' UTC');
  if (notice.kind === 'end') {
    return {
      lead: [text('Session ends at '), time, text('.')],
      rest: [
        text(
          'A session lasts at most 8 hours from sign in and cannot be extended. To keep acknowledging after that, sign in again.',
        ),
      ],
    };
  }
  if (notice.failed) {
    return {
      lead: [text('Not confirmed.')],
      rest: [
        text('The server could not be reached or had a problem, so the session can still end at '),
        time,
        text('. Use Stay signed in to try again.'),
      ],
    };
  }
  return {
    lead: [text('Session ending soon.')],
    rest: [
      text('The session can end at '),
      time,
      text(
        ', 30 minutes after this page last reached the server. Use Stay signed in at the top of the page to keep it.',
      ),
    ],
  };
}

/** The notice as one sentence, for the line that speaks it. */
export function noticeText(notice: SessionNotice): string {
  const { lead, rest } = noticeParts(notice);
  const plain = (s: Segment[]) =>
    s.map((x) => (x.kind === 'text' ? x.text : x.kind === 'time' ? x.label : x.unit)).join('');
  return `${plain(lead)} ${plain(rest)}`;
}

/**
 * Warns before either session time limit can end the session: the idle limit, counted from the last request this tab
 * sent that was answered below 500, and the 8 hour end from the session answer. One notice at a time, for the limit
 * that comes first; Stay signed in is offered only for the idle limit. When a limit passes with no answered request
 * the session is checked once, a minute after it. Nothing here moves focus except Stay signed in.
 */
@Injectable({ providedIn: 'root' })
export class SessionLimits {
  private readonly session = inject(SessionService);
  private readonly activity = inject(SessionActivity);
  private readonly clock = inject(Clock);
  private readonly tick = signal(this.clock.read());
  private readonly stayState = signal<'idle' | 'checking' | 'failed'>('idle');
  private readonly lineState = signal('');
  private timer: ReturnType<typeof setTimeout> | undefined;
  private checkedFor: number | null = null;
  /** Stay signed in confirmed the session: its line outlives the notice it removed. */
  private keepLine = false;

  /** The session area's notice line: the notice once when it appears, and the results of Stay signed in. */
  readonly line = this.lineState.asReadonly();
  readonly staying = computed(() => this.stayState() === 'checking');

  private readonly limits = computed<Limits | null>(() => {
    if (!this.session.signedIn()) {
      return null;
    }
    const last = this.activity.lastSentAt();
    const expiry = this.session.expiry();
    return {
      idleEnd: last === null ? null : last + IDLE_LIMIT_MS,
      end: expiry,
    };
  });

  readonly notice = computed<SessionNotice | null>(() => {
    const limits = this.limits();
    if (!limits || this.session.signingOut()) {
      return null;
    }
    const now = this.tick();
    const { idleEnd, end } = limits;
    if (end && (idleEnd === null || end.localAt <= idleEnd)) {
      return now >= end.localAt - END_WARNING_MS ? { kind: 'end', endsAt: end.at } : null;
    }
    if (idleEnd !== null && now >= idleEnd - IDLE_WARNING_MS) {
      return { kind: 'idle', endsAt: idleEnd, failed: this.stayState() === 'failed' };
    }
    return null;
  });

  constructor() {
    effect(() => {
      const limits = this.limits();
      const now = this.tick();
      // The tick only wakes this; the time is read again, since the effect may run a while after the wake.
      untracked(() => this.schedule(limits, Math.max(now, this.clock.read())));
    });
    let shown: string | null = null;
    effect(() => {
      const notice = this.notice();
      untracked(() => {
        const key = notice ? `${notice.kind} ${notice.endsAt}` : null;
        if (key === shown) {
          return;
        }
        if (notice && (shown === null || !this.keepLine)) {
          this.lineState.set(noticeText(notice));
        } else if (!notice) {
          this.stayState.set('idle');
          if (!this.keepLine) {
            this.lineState.set('');
          }
        }
        this.keepLine = false;
        shown = key;
      });
    });
    effect(() => {
      if (!this.session.signedIn()) {
        untracked(() => {
          this.lineState.set('');
          this.stayState.set('idle');
        });
      }
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  /** Asks the API for the session, which counts as activity. Usable any number of times. */
  async stay(): Promise<void> {
    if (this.stayState() === 'checking') {
      return;
    }
    this.stayState.set('checking');
    this.lineState.set('Checking the session.');
    const probe = await this.session.probe();
    if (probe.kind === 'signed_in') {
      this.stayState.set('idle');
      this.keepLine = true;
      this.lineState.set('You are still signed in.');
      this.session.focusWho();
    } else if (probe.kind === 'anonymous') {
      this.stayState.set('idle');
      this.session.end('check');
    } else {
      this.stayState.set('failed');
      const notice = this.notice();
      if (notice) {
        this.lineState.set(noticeText(notice));
      }
    }
  }

  private schedule(limits: Limits | null, now: number): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    if (!limits) {
      return;
    }
    const { idleEnd, end } = limits;
    const first = Math.min(idleEnd ?? Infinity, end?.localAt ?? Infinity);
    if (first === Infinity) {
      return;
    }
    const last = this.activity.lastSentAt();
    if (
      now >= first + LIMIT_CHECK_DELAY_MS &&
      this.checkedFor !== first &&
      (last === null || last < first)
    ) {
      this.checkedFor = first;
      void this.session.check();
    }
    const wakes = [
      end ? end.localAt - END_WARNING_MS : Infinity,
      idleEnd !== null ? idleEnd - IDLE_WARNING_MS : Infinity,
      this.checkedFor === first ? Infinity : first + LIMIT_CHECK_DELAY_MS,
    ].filter((t) => t > now);
    if (wakes.length) {
      this.timer = setTimeout(() => this.tick.set(this.clock.read()), Math.min(...wakes) - now);
    }
  }
}
