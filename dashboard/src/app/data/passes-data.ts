import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Apollo } from 'apollo-angular';

import { WATCHLIST_PASSES, WatchlistPassesObject } from './queries';

/**
 * The watchlist's passes, fetched only when asked: each request costs the server about a second of computation,
 * so it is not polled and not tied to pausing updates.
 */
@Injectable({ providedIn: 'root' })
export class PassesData {
  private readonly apollo = inject(Apollo);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dataState = signal<WatchlistPassesObject[] | undefined>(undefined);
  private readonly loadingState = signal(false);
  private readonly failedState = signal<number | undefined>(undefined);

  /** The newest answer, kept when a later request fails. */
  readonly data = this.dataState.asReadonly();
  readonly loading = this.loadingState.asReadonly();
  /** Browser time of the newest failed request, cleared by the next answer. */
  readonly failedAt = this.failedState.asReadonly();

  /** Sends one request, or nothing and false while one is in flight. */
  load(): boolean {
    if (this.loadingState()) {
      return false;
    }
    this.loadingState.set(true);
    this.apollo
      .query({ query: WATCHLIST_PASSES, fetchPolicy: 'no-cache', errorPolicy: 'all' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        // A failed object comes back as null passes with an error at its path, and the rest still answers, so an
        // answer with a watchlist is kept whatever errors come with it.
        next: (result) => {
          const watchlist = result.data?.watchlist;
          if (Array.isArray(watchlist)) {
            this.dataState.set(watchlist);
            this.failedState.set(undefined);
          } else {
            this.failedState.set(Date.now());
          }
          this.loadingState.set(false);
        },
        error: () => {
          this.failedState.set(Date.now());
          this.loadingState.set(false);
        },
      });
    return true;
  }
}
