import { DestroyRef, inject, Injectable, Signal, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { OperationVariables, TypedDocumentNode } from '@apollo/client';
import { Apollo, QueryRef } from 'apollo-angular';

import { POLL_INTERVAL_MS } from './graphql';
import {
  RECENT_ALERTS,
  RECENT_ALERTS_PAGE,
  RecentAlertsData,
  SCREENING_CURRENT,
  ScreeningCurrentData,
  SPACE_WEATHER_CURRENT,
  SpaceWeatherCurrentData,
} from './queries';

export interface Live<T> {
  /** The newest complete answer, kept when a later refresh fails. */
  readonly data: Signal<T | undefined>;
  /** Browser time of the newest failed refresh, cleared by the next good one. */
  readonly failedAt: Signal<number | undefined>;
}

/** Owns the dashboard's queries so each is polled once, whichever components read it. */
@Injectable({ providedIn: 'root' })
export class DashboardData {
  private readonly apollo = inject(Apollo);
  private readonly destroyRef = inject(DestroyRef);
  private readonly pollInterval = inject(POLL_INTERVAL_MS);
  private readonly refs: QueryRef<unknown, OperationVariables>[] = [];
  private readonly pausedState = signal(false);

  /** True while the viewer has paused updates (WCAG 2.2.2): no query is polled until they resume. */
  readonly paused = this.pausedState.asReadonly();

  readonly spaceWeather = this.watch<SpaceWeatherCurrentData, Record<string, never>>(
    SPACE_WEATHER_CURRENT,
    {},
  );
  readonly screening = this.watch<ScreeningCurrentData, Record<string, never>>(
    SCREENING_CURRENT,
    {},
  );
  readonly alerts = this.watch<RecentAlertsData, { limit: number }>(RECENT_ALERTS, {
    limit: RECENT_ALERTS_PAGE,
  });

  pause(): void {
    this.pausedState.set(true);
    this.refs.forEach((ref) => ref.stopPolling());
  }

  /** Resumes polling and refreshes at once, so the page does not wait a full interval to be current. */
  resume(): void {
    this.pausedState.set(false);
    for (const ref of this.refs) {
      if (this.pollInterval > 0) {
        ref.startPolling(this.pollInterval);
      }
      ref.refetch().catch(() => undefined);
    }
  }

  private watch<T, V extends OperationVariables>(
    query: TypedDocumentNode<T, V>,
    variables: V,
  ): Live<T> {
    const data = signal<T | undefined>(undefined);
    const failedAt = signal<number | undefined>(undefined);
    const ref = this.apollo.watchQuery<T, V>({ query, variables, pollInterval: this.pollInterval });
    this.refs.push(ref as unknown as QueryRef<unknown, OperationVariables>);
    ref.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((result) => {
      // A refresh in flight is reported as loading with the cached answer attached; it is not an answer.
      if (result.loading) {
        return;
      }
      if (result.error) {
        failedAt.set(Date.now());
      } else if (result.dataState === 'complete') {
        data.set(result.data as T);
        failedAt.set(undefined);
      }
    });
    return { data: data.asReadonly(), failedAt: failedAt.asReadonly() };
  }
}
