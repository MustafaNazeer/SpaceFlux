import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { firstValueFrom, timeout, TimeoutError } from 'rxjs';

import { KeptNotes } from '../ack/kept-notes';
import { Clock } from '../data/clock';
import { parseUtc } from '../format';
import { SessionActivity } from './session-activity';

export type SessionState =
  | { kind: 'checking' }
  | { kind: 'signed_in'; username: string }
  | { kind: 'anonymous' }
  /** A session ended without a sign out (idle, the 8 hour limit, or a newer sign in); read only until the next one. */
  | { kind: 'ended' }
  | { kind: 'unknown' };

export const SESSION_PATH = '/api/auth/session';
export const LOGIN_PATH = '/api/auth/login';
export const LOGOUT_PATH = '/api/auth/logout';

/** How long a session check waits for its answer: at startup, for Stay signed in, and after a refused request. */
export const SESSION_TIMEOUT_MS = new InjectionToken<number>('SESSION_TIMEOUT_MS', {
  factory: () => 10_000,
});
/** How long after a session check that got no usable answer the next one is sent. */
export const SESSION_RETRY_MS = new InjectionToken<number>('SESSION_RETRY_MS', {
  factory: () => 30_000,
});
/**
 * How long a sign in, sign out or acknowledgement waits for its answer. Longer than the API's own worst case, a
 * database that does not answer, which takes about 30 seconds to fail (docs/api/rest.md, common rules).
 */
export const ACTION_TIMEOUT_MS = new InjectionToken<number>('ACTION_TIMEOUT_MS', {
  factory: () => 35_000,
});

/** The header's status line. `lead` is the bold phrase of a failure; a line that is not a failure is not shown. */
export interface HeaderLine {
  lead: string;
  text: string;
  failure: boolean;
}

export const EMPTY_LINE: HeaderLine = { lead: '', text: '', failure: false };

/** Where the header should put focus next; `seq` tells two requests for the same target apart. */
export interface FocusRequest {
  target: 'sign_in' | 'sign_out' | 'who';
  /** Only when focus was lost, as when a background check ends the session under the reader. */
  onlyIfLost: boolean;
  seq: number;
}

export type SignInResult =
  | { kind: 'signed_in' }
  | { kind: 'wrong' }
  | { kind: 'backoff'; seconds: number }
  | { kind: 'backoff_later' }
  /** HTTP 400, or a 403 whose session check found no live session. */
  | { kind: 'refused' }
  /** No answer, a lost connection or a server error, and the session check found no session. */
  | { kind: 'no_session' }
  /** No answer, a lost connection or a server error, and the session check failed too. */
  | { kind: 'not_confirmed' };

/** Why the page drops every principal and note: a sign out in this tab, or a session that ended on its own. */
export type ClearReason = 'sign_out' | 'ended';

/** The 8 hour end of the session: as the API wrote it, and when it falls by this browser's clock. */
export interface SessionExpiry {
  /** expires_at as written by the API, in milliseconds. */
  at: number;
  /** The same instant on this browser's clock, from the time left at the answer, so a wrong clock does not move it. */
  localAt: number;
}

/** A Retry-After the page is waiting out, so a reopened form still says when Sign in works again. */
export interface Backoff {
  until: number;
  seconds: number;
}

/** What one session check found: who is signed in, nobody, or no usable answer. */
export type Probe =
  | { kind: 'signed_in'; username: string; expiry: SessionExpiry | null }
  | { kind: 'anonymous' }
  | { kind: 'error' };

interface SessionBody {
  username: string;
  expires_at?: string;
}

const statusOf = (e: unknown) => (e instanceof HttpErrorResponse ? e.status : 0);

/** No answer, a lost connection or a server error: the request may have gone through. */
const unknownOutcome = (e: unknown) => {
  if (e instanceof TimeoutError) {
    return true;
  }
  const status = statusOf(e);
  return status === 0 || status >= 500;
};

/** Seconds from a Retry-After header, or null when it is missing or not a whole number of seconds from 1 up. */
export function retryAfterSeconds(value: string | null): number | null {
  if (value === null || !/^\d+$/.test(value.trim())) {
    return null;
  }
  const n = Number(value.trim());
  return n >= 1 && Number.isSafeInteger(n) ? n : null;
}

/**
 * The 8 hour end from a session answer: expires_at, placed on this browser's clock by the time left between the
 * response's Date header and expires_at. Without a usable Date header this browser's clock is trusted.
 */
export function expiryOf(
  body: SessionBody,
  dateHeader: string | null,
  receivedAt: number,
): SessionExpiry | null {
  const at = parseUtc(body.expires_at);
  if (Number.isNaN(at)) {
    return null;
  }
  const serverNow = dateHeader === null ? NaN : Date.parse(dateHeader);
  return { at, localAt: Number.isNaN(serverNow) ? at : receivedAt + (at - serverNow) };
}

/**
 * Who is signed in, and the operator's sign in and sign out. The startup call also sets the XSRF-TOKEN cookie that
 * every unsafe request must echo in the X-XSRF-TOKEN header, so startup waits for it, but never longer than the
 * timeout: after that the page shows the anonymous read only view. A check with no usable answer is repeated until
 * the API answers. Sign in, sign out and acknowledgement are never resent by the page. Every change of who is
 * signed in moves a generation on, so an answer to a check sent before it can never overwrite the newer state.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly timeoutMs = inject(SESSION_TIMEOUT_MS);
  private readonly retryMs = inject(SESSION_RETRY_MS);
  private readonly actionTimeoutMs = inject(ACTION_TIMEOUT_MS);
  private readonly clock = inject(Clock);
  private readonly kept = inject(KeptNotes);
  private readonly activity = inject(SessionActivity);
  private readonly current = signal<SessionState>({ kind: 'checking' });
  private readonly lineState = signal<HeaderLine>(EMPTY_LINE);
  private readonly focusState = signal<FocusRequest | null>(null);
  private readonly signingOutState = signal(false);
  private readonly expiryState = signal<SessionExpiry | null>(null);
  private readonly backoffState = signal<Backoff | null>(null);
  private retry: ReturnType<typeof setTimeout> | undefined;
  private backoffTimer: ReturnType<typeof setTimeout> | undefined;
  private generation = 0;
  private checking = false;
  private readonly signedInListeners = new Set<() => void>();
  private readonly clearedListeners = new Set<(reason: ClearReason) => void>();

  readonly state = this.current.asReadonly();
  readonly signedIn = computed(() => this.current().kind === 'signed_in');
  /** The header's persistent status line. */
  readonly line = this.lineState.asReadonly();
  readonly focus = this.focusState.asReadonly();
  /** True while a sign out request is out. */
  readonly signingOut = this.signingOutState.asReadonly();
  /** The session's 8 hour end, from the newest session answer; null when unknown or signed out. */
  readonly expiry = this.expiryState.asReadonly();
  /** The sign in backoff being waited out, or null. */
  readonly backoff = this.backoffState.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.retry);
      clearTimeout(this.backoffTimer);
    });
  }

  /** Calls `fn` after each sign in, once the name is known. Returns the function that stops it. */
  onSignedIn(fn: () => void): () => void {
    this.signedInListeners.add(fn);
    return () => this.signedInListeners.delete(fn);
  }

  /**
   * Calls `fn` whenever the page must drop every principal and note it holds: on sign out and when the session is
   * found ended. It runs at once, before any request, so nothing an operator wrote stays on screen.
   */
  onOperatorCleared(fn: (reason: ClearReason) => void): () => void {
    this.clearedListeners.add(fn);
    return () => this.clearedListeners.delete(fn);
  }

  /** The startup check, repeated while it gets no usable answer. Ignored once a newer change has happened. */
  async load(): Promise<void> {
    clearTimeout(this.retry);
    const generation = this.generation;
    const before = this.current().kind;
    let probe: Probe | 'timeout';
    try {
      probe = await this.ask();
    } catch (e) {
      probe = e instanceof TimeoutError ? 'timeout' : { kind: 'error' };
    }
    if (generation !== this.generation) {
      return;
    }
    if (probe !== 'timeout' && probe.kind === 'signed_in') {
      this.current.set({ kind: 'signed_in', username: probe.username });
      this.expiryState.set(probe.expiry);
      if (before !== 'checking') {
        this.signedInListeners.forEach((fn) => fn());
      }
      return;
    }
    if (probe !== 'timeout' && probe.kind === 'anonymous') {
      this.current.set({ kind: before === 'ended' ? 'ended' : 'anonymous' });
      return;
    }
    // A timeout shows the read only view; a network error or a server error leaves the state unknown. Either way
    // the API has not answered who is signed in, so the call is tried again.
    const readOnly = probe === 'timeout' && (before === 'checking' || before === 'anonymous');
    this.current.set({ kind: readOnly ? 'anonymous' : 'unknown' });
    this.retry = setTimeout(() => void this.load(), this.retryMs);
  }

  /** One session check. The answer also sets a fresh XSRF-TOKEN cookie when the request carried none. */
  async probe(): Promise<Probe> {
    try {
      return await this.ask();
    } catch {
      return { kind: 'error' };
    }
  }

  /**
   * Posts the credentials as a form (docs/api/rest.md, section 7) and, on success, asks the session for the name.
   * Never resent: after a refused request token or an unknown outcome the session is checked once and its answer
   * shown.
   */
  async signIn(username: string, password: string): Promise<SignInResult> {
    this.lineState.set(EMPTY_LINE);
    // URLSearchParams writes a space as + and a + as %2B, as a form body needs; HttpParams would leave + as it is.
    const body = new URLSearchParams({ username, password }).toString();
    try {
      await firstValueFrom(
        this.http
          .post(LOGIN_PATH, body, {
            headers: new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' }),
            responseType: 'text',
          })
          .pipe(timeout({ first: this.actionTimeoutMs })),
      );
    } catch (e) {
      if (unknownOutcome(e)) {
        return this.afterUnknownSignIn();
      }
      const status = statusOf(e);
      if (status === 401) {
        return { kind: 'wrong' };
      }
      if (status === 429) {
        const seconds = retryAfterSeconds((e as HttpErrorResponse).headers.get('Retry-After'));
        if (seconds === null) {
          return { kind: 'backoff_later' };
        }
        this.startBackoff(seconds);
        return { kind: 'backoff', seconds };
      }
      if (status === 403) {
        const probe = await this.probe();
        if (probe.kind === 'signed_in') {
          this.signedInAs(probe);
          return { kind: 'signed_in' };
        }
        this.keepSignedOut(probe);
      }
      return { kind: 'refused' };
    }
    return this.afterUnknownSignIn();
  }

  async signOut(): Promise<void> {
    if (this.signingOutState()) {
      return;
    }
    this.signingOutState.set(true);
    this.lineState.set({ lead: '', text: 'Signing out.', failure: false });
    try {
      await firstValueFrom(
        this.http
          .post(LOGOUT_PATH, null, { responseType: 'text' })
          .pipe(timeout({ first: this.actionTimeoutMs })),
      );
    } catch {
      this.signingOutState.set(false);
      this.lineState.set({
        lead: 'Sign out not confirmed.',
        text: 'The server could not be reached or had a problem, so you may still be signed in. Use Sign out to try again.',
        failure: true,
      });
      return;
    }
    this.signingOutState.set(false);
    this.generation++;
    this.clearOperator('sign_out');
    this.kept.clear();
    this.expiryState.set(null);
    this.current.set({ kind: 'anonymous' });
    this.lineState.set({ lead: '', text: 'Signed out.', failure: false });
    this.requestFocus('sign_in', false);
  }

  /**
   * The API answered as if there were no session: switch to the signed out view as on sign out, keeping any note
   * being written. `ack` is an acknowledgement that was refused for it; `check` is a session check, which moves
   * focus only if it was lost.
   */
  end(reason: 'ack' | 'check'): void {
    this.generation++;
    const keptBefore = this.kept.size();
    this.clearOperator('ended');
    const keptNow = this.kept.size() - keptBefore;
    this.expiryState.set(null);
    this.current.set({ kind: 'ended' });
    const base =
      reason === 'ack'
        ? 'Your session has ended, so the acknowledgement was not saved. Sign in again to acknowledge.'
        : 'Your session has ended. Sign in again to acknowledge.';
    const note =
      keptNow === 0
        ? ''
        : keptNow === 1
          ? ' The note you were writing is kept, not saved.'
          : ' The notes you were writing are kept, not saved.';
    this.lineState.set({ lead: '', text: base + note, failure: false });
    this.requestFocus('sign_in', reason === 'check');
  }

  /** A session check while signed in: a 401 ends the session; any other answer changes nothing. */
  async check(): Promise<void> {
    if (!this.signedIn() || this.checking) {
      return;
    }
    this.checking = true;
    const generation = this.generation;
    const probe = await this.probe();
    this.checking = false;
    if (generation !== this.generation || !this.signedIn()) {
      return;
    }
    if (probe.kind === 'anonymous') {
      this.end('check');
    } else if (probe.kind === 'signed_in') {
      this.expiryState.set(probe.expiry);
    }
  }

  /** Stay signed in moved focus to the "Signed in as" line. */
  focusWho(): void {
    this.requestFocus('who', false);
  }

  private async ask(): Promise<Probe> {
    try {
      const response: HttpResponse<SessionBody> = await firstValueFrom(
        this.http
          .get<SessionBody>(SESSION_PATH, { observe: 'response' })
          .pipe(timeout({ first: this.timeoutMs })),
      );
      const body = response.body!;
      return {
        kind: 'signed_in',
        username: body.username,
        expiry: expiryOf(body, response.headers.get('Date'), this.clock.read()),
      };
    } catch (e) {
      if (statusOf(e) === 401) {
        return { kind: 'anonymous' };
      }
      throw e;
    }
  }

  /** After a login whose outcome the page cannot know, or a 204, the session says what happened. */
  private async afterUnknownSignIn(): Promise<SignInResult> {
    const probe = await this.probe();
    if (probe.kind === 'signed_in') {
      this.signedInAs(probe);
      return { kind: 'signed_in' };
    }
    if (probe.kind === 'anonymous') {
      this.keepSignedOut(probe);
      return { kind: 'no_session' };
    }
    // The page cannot tell whether a session exists, so the header says so until the repeated check answers.
    this.generation++;
    this.current.set({ kind: 'unknown' });
    clearTimeout(this.retry);
    this.retry = setTimeout(() => void this.load(), this.retryMs);
    return { kind: 'not_confirmed' };
  }

  private keepSignedOut(probe: Probe): void {
    if (probe.kind === 'anonymous' && this.current().kind !== 'ended') {
      this.current.set({ kind: 'anonymous' });
    }
  }

  private signedInAs(probe: Extract<Probe, { kind: 'signed_in' }>): void {
    clearTimeout(this.retry);
    this.generation++;
    this.expiryState.set(probe.expiry);
    this.current.set({ kind: 'signed_in', username: probe.username });
    this.lineState.set({ lead: '', text: `Signed in as ${probe.username}.`, failure: false });
    this.requestFocus('sign_out', false);
    this.signedInListeners.forEach((fn) => fn());
  }

  private startBackoff(seconds: number): void {
    clearTimeout(this.backoffTimer);
    this.backoffState.set({ until: this.clock.read() + seconds * 1000, seconds });
    this.backoffTimer = setTimeout(() => this.backoffState.set(null), seconds * 1000);
  }

  private clearOperator(reason: ClearReason): void {
    this.clearedListeners.forEach((fn) => fn(reason));
  }

  private requestFocus(target: FocusRequest['target'], onlyIfLost: boolean): void {
    this.focusState.set({ target, onlyIfLost, seq: (this.focusState()?.seq ?? 0) + 1 });
  }
}
