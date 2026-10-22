import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SESSION_PATH, SessionService } from '../app/session/session.service';

import { respond } from './graphql';
import { ALERT_APPROACH } from './fixtures';

/** The committed close approach example's event id. */
export const EVENT_ID = ALERT_APPROACH.alert.event_id;

/** Answers a GraphQL POST with the given acknowledgement per event_id; an id not in the map is not stored. */
export function answerAcks(req: TestRequest, acks: Record<string, object | null>): void {
  const body = req.request.body as { query: string; variables: Record<string, string> };
  const root = {
    alert: ({ event_id }: { event_id: string }) =>
      event_id in acks
        ? { ...ALERT_APPROACH.alert, event_id, acknowledgement: acks[event_id] }
        : null,
  };
  req.flush(respond(body.query, root, body.variables));
}

/** Starts the session as the signed in operator, answering the startup check. */
export async function signedIn(http: HttpTestingController, username = 'operator'): Promise<void> {
  const loaded = TestBed.inject(SessionService).load();
  http.expectOne(SESSION_PATH).flush({ username });
  await loaded;
}
