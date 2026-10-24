import { PlatformLocation } from '@angular/common';
import {
  HttpEvent,
  HttpEventType,
  HttpHandlerFn,
  HttpRequest,
  HttpXsrfTokenExtractor,
  provideHttpClient,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import { EnvironmentProviders, inject, Injectable } from '@angular/core';
import {
  catchError,
  defaultIfEmpty,
  filter,
  finalize,
  map,
  Observable,
  of,
  share,
  switchMap,
  take,
  tap,
  timeout,
} from 'rxjs';

import { SESSION_PATH, SESSION_TIMEOUT_MS } from './session.service';
import { sessionActivityInterceptor } from './session-activity';

/** The API's CSRF cookie and header names, which are also Angular's defaults (docs/api/rest.md, section 7). */
export const XSRF_COOKIE = 'XSRF-TOKEN';
export const XSRF_HEADER = 'X-XSRF-TOKEN';

/** The methods Spring Security's default CSRF matcher lets through without a token. */
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

/** The one session fetch that every unsafe request sent without a token while it is out waits for. */
@Injectable({ providedIn: 'root' })
export class XsrfRefresh {
  private pending: Observable<void> | null = null;

  /**
   * Emits once and completes when the session has answered in any way, failed, ended without an answer, or taken
   * longer than the session timeout. Cleared before it emits, and whenever it ends or is given up, so a request that
   * finds the token gone again later starts a new fetch.
   */
  fetch(next: HttpHandlerFn, timeoutMs: number): Observable<void> {
    if (this.pending === null) {
      const fetch: Observable<void> = next(new HttpRequest('GET', SESSION_PATH)).pipe(
        filter((event) => event.type === HttpEventType.Response),
        take(1),
        timeout({ first: timeoutMs }),
        map(() => undefined),
        catchError(() => of(undefined)),
        defaultIfEmpty(undefined),
        tap(() => this.release(fetch)),
        finalize(() => this.release(fetch)),
        share(),
      );
      this.pending = fetch;
    }
    return this.pending;
  }

  private release(fetch: Observable<void>): void {
    if (this.pending === fetch) {
      this.pending = null;
    }
  }
}

function sameOriginApi(url: string, href: string): boolean {
  try {
    const { origin } = new URL(href);
    const target = new URL(url, origin);
    return target.origin === origin && target.pathname.startsWith('/api/');
  } catch {
    return false;
  }
}

/**
 * The API expires the XSRF-TOKEN cookie on sign out and sends no new one, and over HTTPS Clear-Site-Data wipes it too,
 * in every tab. An unsafe API request sent without it is refused, so this first sends one session GET, whose answer
 * (even a 401) sets a fresh cookie, then sends the request once with it. Angular's own XSRF interceptor runs before
 * this one, so the header is set here. If no token comes back the request goes as it is and is refused as before.
 */
export function xsrfRefreshInterceptor(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  if (SAFE_METHODS.has(req.method) || req.headers.has(XSRF_HEADER)) {
    return next(req);
  }
  const tokens = inject(HttpXsrfTokenExtractor);
  if (tokens.getToken() !== null || !sameOriginApi(req.url, inject(PlatformLocation).href)) {
    return next(req);
  }
  return inject(XsrfRefresh)
    .fetch(next, inject(SESSION_TIMEOUT_MS))
    .pipe(
      switchMap(() => {
        const token = tokens.getToken();
        return next(token === null ? req : req.clone({ setHeaders: { [XSRF_HEADER]: token } }));
      }),
    );
}

/** HttpClient as the dashboard uses it: the API's CSRF names, the token refresh, and the session's idle clock. */
export function provideApiHttpClient(): EnvironmentProviders {
  return provideHttpClient(
    withXsrfConfiguration({ cookieName: XSRF_COOKIE, headerName: XSRF_HEADER }),
    // First, so the session fetch it sends also passes the idle clock, which counts every answer below 500.
    withInterceptors([xsrfRefreshInterceptor, sessionActivityInterceptor]),
  );
}
