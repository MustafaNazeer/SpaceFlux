import {
  HttpClient,
  HttpErrorResponse,
  HttpParameterCodec,
  HttpParams,
} from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { gql, TypedDocumentNode } from 'apollo-angular';
import { Apollo } from 'apollo-angular';
import { firstValueFrom, Observable, timeout, TimeoutError } from 'rxjs';
import { map } from 'rxjs/operators';

import { Acknowledgement } from '../data/queries';
import { ACTION_TIMEOUT_MS, SessionService } from '../session/session.service';
import { isAcknowledged, rowOf } from './note';

export const ACK_PATH = '/api/alerts/acknowledgements';

/** Alerts asked for in one acknowledgement request; with Apollo's __typename each costs 9 of the 200 field limit. */
export const ACK_BATCH = 20;

/** Rows per page of an alert's acknowledgement history. */
export const HISTORY_PAGE = 20;

export type AckAction = 'acknowledge' | 'unacknowledge';

/**
 * How one acknowledgement press ended. `already` and `refused_state` are the two kinds of 409, told apart by the alert's
 * fresh state, never by the problem text. The `unknown_*` results follow a request with no answer in time, a lost
 * connection or a server error: the row may or may not have been written, so the alert's state is fetched again.
 */
export type AckResult =
  | { kind: 'saved'; ack: Acknowledgement }
  | { kind: 'ended' }
  /** HTTP 400, 415, any other refusal, and every 403 that is not an ended session. */
  | { kind: 'refused' }
  | { kind: 'not_found' }
  | { kind: 'already'; ack: Acknowledgement | null }
  | { kind: 'refused_state'; ack: Acknowledgement | null }
  | { kind: 'conflict_unknown' }
  | { kind: 'unknown_done'; ack: Acknowledgement | null }
  | { kind: 'unknown_not'; ack: Acknowledgement | null }
  | { kind: 'unknown' };

export interface HistoryPage {
  items: Acknowledgement[];
  next: string | null;
}

/** Percent encodes every reserved character, where HttpParams' default codec leaves + and / as they are. */
const STRICT: HttpParameterCodec = {
  encodeKey: encodeURIComponent,
  encodeValue: encodeURIComponent,
  decodeKey: decodeURIComponent,
  decodeValue: decodeURIComponent,
};

export function strictParams(values: Record<string, string | number>): HttpParams {
  return new HttpParams({ encoder: STRICT, fromObject: values });
}

/** One aliased alert(event_id) per id, selecting only the acknowledgement. */
export function acknowledgementsQuery(count: number): TypedDocumentNode<AcknowledgementsData> {
  const vars = Array.from({ length: count }, (_, i) => `$e${i}: String!`).join(', ');
  const fields = Array.from(
    { length: count },
    (_, i) =>
      `a${i}: alert(event_id: $e${i}) { event_id acknowledgement { action acted_at principal note } }`,
  ).join('\n');
  return gql(`query Acknowledgements(${vars}) {\n${fields}\n}`);
}

type AcknowledgementsData = Record<
  string,
  { event_id: string; acknowledgement: Acknowledgement | null } | null
>;

const statusOf = (e: unknown) => (e instanceof HttpErrorResponse ? e.status : 0);

/** True when the alert's state is the one the action asks for. */
const reached = (action: AckAction, ack: Acknowledgement | null) =>
  isAcknowledged(ack) === (action === 'acknowledge');

/** The acknowledgement endpoints (docs/api/rest.md, sections 8 and 9) and the GraphQL read of current states. */
@Injectable({ providedIn: 'root' })
export class AckApi {
  private readonly http = inject(HttpClient);
  private readonly apollo = inject(Apollo);
  private readonly session = inject(SessionService);
  private readonly timeoutMs = inject(ACTION_TIMEOUT_MS);

  /** Acknowledges or unacknowledges one alert. Never resent: every outcome is the operator's to act on. */
  async act(eventId: string, action: AckAction, note?: string): Promise<AckResult> {
    const body = note === undefined ? { action } : { action, note };
    let row: Parameters<typeof rowOf>[0];
    try {
      row = await firstValueFrom(
        this.http
          .post<Parameters<typeof rowOf>[0]>(ACK_PATH, body, {
            params: strictParams({ event_id: eventId }),
          })
          .pipe(timeout({ first: this.timeoutMs })),
      );
    } catch (e) {
      const status = statusOf(e);
      if (e instanceof TimeoutError || status === 0 || status >= 500) {
        return this.afterUnknown(eventId, action);
      }
      switch (status) {
        case 401:
          return { kind: 'ended' };
        case 403: {
          // A refused request token, an ended session, or no operator configured: one session check tells them
          // apart and sets a fresh token. A 403 is answered before any write, so it is always a refusal.
          const probe = await this.session.probe();
          return probe.kind === 'anonymous' ? { kind: 'ended' } : { kind: 'refused' };
        }
        case 404:
          return { kind: 'not_found' };
        case 409:
          return this.afterConflict(eventId, action);
        default:
          return { kind: 'refused' };
      }
    }
    return { kind: 'saved', ack: rowOf(row) };
  }

  /**
   * The current acknowledgement of each alert, by event_id, in requests of ACK_BATCH. An alert that is not stored
   * is left out of the map. Rejects when any request fails.
   */
  async acknowledgements(ids: readonly string[]): Promise<Map<string, Acknowledgement | null>> {
    const chunks: string[][] = [];
    for (let i = 0; i < ids.length; i += ACK_BATCH) {
      chunks.push(ids.slice(i, i + ACK_BATCH));
    }
    const answers = await Promise.all(
      chunks.map((chunk) =>
        firstValueFrom(
          this.apollo
            .query({
              query: acknowledgementsQuery(chunk.length),
              variables: Object.fromEntries(chunk.map((id, i) => [`e${i}`, id])),
              fetchPolicy: 'no-cache',
            })
            .pipe(timeout({ first: this.timeoutMs })),
        ).then((result) => {
          if (!result.data) {
            throw new Error('no data');
          }
          return chunk.map((id, i) => [id, result.data![`a${i}`]] as const);
        }),
      ),
    );
    const found = new Map<string, Acknowledgement | null>();
    for (const [id, alert] of answers.flat()) {
      if (alert) {
        found.set(id, alert.acknowledgement ? rowOf(alert.acknowledgement) : null);
      }
    }
    return found;
  }

  /** One page of an alert's acknowledgement history, newest first. */
  history(eventId: string, after: string | null): Observable<HistoryPage> {
    const values: Record<string, string | number> = { event_id: eventId, limit: HISTORY_PAGE };
    if (after !== null) {
      values['after'] = after;
    }
    return this.http
      .get<{ items: Parameters<typeof rowOf>[0][]; next?: string | null }>(ACK_PATH, {
        params: strictParams(values),
      })
      .pipe(map((body) => ({ items: body.items.map(rowOf), next: body.next ?? null })));
  }

  private async fresh(eventId: string): Promise<{ ack: Acknowledgement | null } | null> {
    try {
      const found = await this.acknowledgements([eventId]);
      return found.has(eventId) ? { ack: found.get(eventId)! } : null;
    } catch {
      return null;
    }
  }

  private async afterConflict(eventId: string, action: AckAction): Promise<AckResult> {
    const fresh = await this.fresh(eventId);
    if (!fresh) {
      return { kind: 'conflict_unknown' };
    }
    return reached(action, fresh.ack)
      ? { kind: 'already', ack: fresh.ack }
      : { kind: 'refused_state', ack: fresh.ack };
  }

  private async afterUnknown(eventId: string, action: AckAction): Promise<AckResult> {
    const fresh = await this.fresh(eventId);
    if (!fresh) {
      return { kind: 'unknown' };
    }
    return reached(action, fresh.ack)
      ? { kind: 'unknown_done', ack: fresh.ack }
      : { kind: 'unknown_not', ack: fresh.ack };
  }
}
