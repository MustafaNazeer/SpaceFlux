import {
  HttpEvent,
  HttpHandlerFn,
  HttpRequest,
  HttpResponse,
  HttpErrorResponse,
} from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/**
 * When the newest request this tab sent was sent, counting only requests answered with a status below 500: those
 * certainly reached the service, and every request the service receives keeps the session's idle limit away.
 */
@Injectable({ providedIn: 'root' })
export class SessionActivity {
  private readonly last = signal<number | null>(null);

  readonly lastSentAt = this.last.asReadonly();

  /** A request sent at `sentAt` was answered below 500. An older answer arriving late never moves the clock back. */
  record(sentAt: number): void {
    const last = this.last();
    if (last === null || sentAt > last) {
      this.last.set(sentAt);
    }
  }
}

/** Feeds SessionActivity from every HTTP answer. */
export function sessionActivityInterceptor(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  const activity = inject(SessionActivity);
  const sentAt = Date.now();
  const reached = (status: number) => status > 0 && status < 500;
  return next(req).pipe(
    tap({
      next: (event) => {
        if (event instanceof HttpResponse && reached(event.status)) {
          activity.record(sentAt);
        }
      },
      error: (error: unknown) => {
        if (error instanceof HttpErrorResponse && reached(error.status)) {
          activity.record(sentAt);
        }
      },
    }),
  );
}
