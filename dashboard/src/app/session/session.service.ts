import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { firstValueFrom, timeout, TimeoutError } from 'rxjs';

export type SessionState =
  | { kind: 'checking' }
  | { kind: 'signed_in'; username: string }
  | { kind: 'anonymous' }
  | { kind: 'unknown' };

export const SESSION_PATH = '/api/auth/session';

/** How long startup waits for the session answer before showing the read only view. */
export const SESSION_TIMEOUT_MS = new InjectionToken<number>('SESSION_TIMEOUT_MS', {
  factory: () => 10_000,
});
/** How long after a timed out session call the next one is sent. */
export const SESSION_RETRY_MS = new InjectionToken<number>('SESSION_RETRY_MS', {
  factory: () => 30_000,
});

/**
 * Asks the API who is signed in. The answer also sets the XSRF-TOKEN cookie that every GraphQL POST must echo in
 * the X-XSRF-TOKEN header, so startup waits for it, but never longer than the timeout: after that the page shows
 * the anonymous read only view. A timeout, a network error or a server error is retried until the API answers.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly timeoutMs = inject(SESSION_TIMEOUT_MS);
  private readonly retryMs = inject(SESSION_RETRY_MS);
  private readonly current = signal<SessionState>({ kind: 'checking' });
  private retry: ReturnType<typeof setTimeout> | undefined;

  readonly state = this.current.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.retry));
  }

  async load(): Promise<void> {
    clearTimeout(this.retry);
    try {
      const body = await firstValueFrom(
        this.http.get<{ username: string }>(SESSION_PATH).pipe(timeout({ first: this.timeoutMs })),
      );
      this.current.set({ kind: 'signed_in', username: body.username });
    } catch (e) {
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      if (status === 401) {
        this.current.set({ kind: 'anonymous' });
        return;
      }
      // A timeout shows the read only view; a network error or a server error leaves the state unknown. Either
      // way the API has not answered who is signed in, so the call is tried again.
      this.current.set(e instanceof TimeoutError ? { kind: 'anonymous' } : { kind: 'unknown' });
      this.retry = setTimeout(() => void this.load(), this.retryMs);
    }
  }
}
