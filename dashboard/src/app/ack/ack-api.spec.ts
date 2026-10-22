import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApollo } from 'apollo-angular';
import { addTypenameToDocument } from '@apollo/client/utilities';
import { Kind, OperationDefinitionNode, SelectionSetNode, validate } from 'graphql';

import { apolloOptions, GRAPHQL_PATH } from '../data/graphql';
import { SESSION_PATH } from '../session/session.service';
import { provideFakeSocket } from '../../testing/fake-socket';
import { answerAcks, EVENT_ID } from '../../testing/acks';
import { SCHEMA } from '../../testing/graphql';
import { ACK_BATCH, ACK_PATH, AckApi, acknowledgementsQuery, AckResult } from './ack-api';

const ROW = {
  event_id: EVENT_ID,
  action: 'acknowledge',
  principal: 'operator',
  acted_at: '2026-10-22T10:15:00.123456Z',
  note: 'Checked both element sets.',
};
const ACKED = { action: 'acknowledge', acted_at: ROW.acted_at, principal: 'operator', note: null };

const status = (s: number, headers: Record<string, string> = {}) => ({
  status: s,
  statusText: 'x',
  headers,
});
const tick = async () => {
  for (let i = 0; i < 4; i++) {
    await new Promise((r) => setTimeout(r));
  }
};

describe('AckApi', () => {
  let http: HttpTestingController;
  let api: AckApi;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=tok-2; path=/';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
        ),
        provideHttpClientTesting(),
        provideApollo(apolloOptions),
        provideFakeSocket(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(AckApi);
  });

  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  function post(): TestRequest {
    return http.expectOne((r) => r.method === 'POST' && r.url === ACK_PATH);
  }

  it('posts the action as JSON with the event id percent encoded in the query string and the XSRF header', async () => {
    const result = api.act(EVENT_ID, 'acknowledge');
    const req = post();
    expect(req.request.urlWithParams).toBe(`${ACK_PATH}?event_id=${encodeURIComponent(EVENT_ID)}`);
    expect(req.request.body).toEqual({ action: 'acknowledge' });
    expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('tok-2');
    req.flush(ROW, status(201));
    expect(await result).toEqual({
      kind: 'saved',
      ack: { action: 'acknowledge', acted_at: ROW.acted_at, principal: 'operator', note: ROW.note },
    });
  });

  it('sends a note only when one is given, and unacknowledge with none', async () => {
    void api.act(EVENT_ID, 'acknowledge', 'a \u{1F6F0} note');
    expect(post().request.body).toEqual({ action: 'acknowledge', note: 'a \u{1F6F0} note' });
    void api.act(EVENT_ID, 'unacknowledge');
    expect(post().request.body).toEqual({ action: 'unacknowledge' });
  });

  it.each([
    [400, { kind: 'refused' }],
    [415, { kind: 'refused' }],
    [404, { kind: 'not_found' }],
    [401, { kind: 'ended' }],
  ])('reads %s', async (code, expected) => {
    const result = api.act(EVENT_ID, 'acknowledge');
    post().flush({ status: code }, status(code));
    expect(await result).toEqual(expected as AckResult);
    http.expectNone(SESSION_PATH);
    http.expectNone(GRAPHQL_PATH);
  });

  it.each([500, 502, 503, 504])(
    'reads %s as an unknown outcome: never resent, the alert is fetched again',
    async (code) => {
      const result = api.act(EVENT_ID, 'acknowledge');
      post().flush({ status: code }, status(code));
      await tick();
      answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: ACKED });
      expect(await result).toEqual({ kind: 'unknown_done', ack: ACKED });
      http.expectNone(ACK_PATH);
    },
  );

  it('reads a lost connection as an unknown outcome', async () => {
    const result = api.act(EVENT_ID, 'acknowledge');
    post().error(new ProgressEvent('error'));
    await tick();
    answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: null });
    expect(await result).toEqual({ kind: 'unknown_not', ack: null });
  });

  it('after a 403 checks the session once and never resends: no session is an ended session', async () => {
    const result = api.act(EVENT_ID, 'acknowledge');
    post().flush({ status: 403 }, status(403));
    await tick();
    http.expectOne(SESSION_PATH).flush({ status: 401 }, status(401));
    expect(await result).toEqual({ kind: 'ended' });
    http.expectNone(ACK_PATH);
    http.expectNone(SESSION_PATH);
  });

  it('after a 403 with a live session, or a failed check, reads as refused, since a 403 writes nothing', async () => {
    let result = api.act(EVENT_ID, 'acknowledge');
    post().flush({ status: 403 }, status(403));
    await tick();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    expect(await result).toEqual({ kind: 'refused' });
    result = api.act(EVENT_ID, 'acknowledge');
    post().flush({ status: 403 }, status(403));
    await tick();
    http.expectOne(SESSION_PATH).error(new ProgressEvent('error'));
    expect(await result).toEqual({ kind: 'refused' });
    http.expectNone(ACK_PATH);
  });

  it('after a 409 fetches the alert again and calls it already done when the fresh state is the one asked for', async () => {
    const result = api.act(EVENT_ID, 'acknowledge');
    post().flush({ status: 409, detail: 'cannot be acknowledged' }, status(409));
    await tick();
    answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: ACKED });
    expect(await result).toEqual({ kind: 'already', ack: ACKED });
  });

  it('after a 409 reads a fresh state that differs as refused, whatever the problem text says', async () => {
    const result = api.act(EVENT_ID, 'acknowledge');
    post().flush({ status: 409, detail: 'already acknowledged' }, status(409));
    await tick();
    answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: null });
    expect(await result).toEqual({ kind: 'refused_state', ack: null });
  });

  it('after a 409 on unacknowledge, no row at all counts as already unacknowledged', async () => {
    const result = api.act(EVENT_ID, 'unacknowledge');
    post().flush({ status: 409 }, status(409));
    await tick();
    answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: null });
    expect(await result).toEqual({ kind: 'already', ack: null });
  });

  it('after a 409 whose fetch fails or finds no alert, says the state could not be loaded', async () => {
    let result = api.act(EVENT_ID, 'acknowledge');
    post().flush({ status: 409 }, status(409));
    await tick();
    http.expectOne(GRAPHQL_PATH).error(new ProgressEvent('error'));
    expect(await result).toEqual({ kind: 'conflict_unknown' });
    result = api.act(EVENT_ID, 'acknowledge');
    post().flush({ status: 409 }, status(409));
    await tick();
    answerAcks(http.expectOne(GRAPHQL_PATH), {});
    expect(await result).toEqual({ kind: 'conflict_unknown' });
  });

  describe('when the API does not answer', () => {
    beforeEach(() => vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] }));
    afterEach(() => vi.useRealTimers());

    async function timedOut(action: 'acknowledge' | 'unacknowledge') {
      const result = api.act(EVENT_ID, action);
      const req = post();
      await vi.advanceTimersByTimeAsync(34_999);
      http.expectNone(GRAPHQL_PATH);
      await vi.advanceTimersByTimeAsync(1);
      expect(req.cancelled).toBe(true);
      // Wrapped, so awaiting this helper does not wait for the result itself.
      return { result };
    }

    it('waits 35 s, never resends, and fetches the alert again since the row may be written', async () => {
      const { result } = await timedOut('acknowledge');
      answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: ACKED });
      expect(await result).toEqual({ kind: 'unknown_done', ack: ACKED });
      http.expectNone(ACK_PATH);
    });

    it('reads a fresh state that differs as not reached', async () => {
      const { result } = await timedOut('acknowledge');
      answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: null });
      expect(await result).toEqual({ kind: 'unknown_not', ack: null });
    });

    it('reads a failed fetch as unknown', async () => {
      const { result } = await timedOut('unacknowledge');
      http.expectOne(GRAPHQL_PATH).error(new ProgressEvent('error'));
      expect(await result).toEqual({ kind: 'unknown' });
    });
  });

  it('reads current acknowledgements in requests of 20, leaving out alerts that are not stored', async () => {
    const ids = Array.from({ length: 45 }, (_, i) => `close_approach/1/x/${i}`);
    const found = api.acknowledgements(ids);
    const reqs = http.match(GRAPHQL_PATH);
    expect(reqs.map((r) => Object.keys(r.request.body.variables).length)).toEqual([20, 20, 5]);
    const acks = Object.fromEntries(ids.slice(0, 44).map((id) => [id, ACKED]));
    reqs.forEach((r) => answerAcks(r, acks));
    const map = await found;
    expect(map.size).toBe(44);
    expect(map.get(ids[0])).toEqual(ACKED);
    expect(map.has(ids[44])).toBe(false);
  });

  it('builds a batch query the server schema accepts, within the field count limit with __typename', () => {
    const sent = addTypenameToDocument(acknowledgementsQuery(ACK_BATCH));
    expect(validate(SCHEMA, sent)).toEqual([]);
    // Every field counts, aliases and Apollo's __typename included; the API refuses more than 200.
    const count = (set?: SelectionSetNode): number =>
      (set?.selections ?? []).reduce(
        (n, sel) => n + (sel.kind === Kind.FIELD ? 1 + count(sel.selectionSet) : 0),
        0,
      );
    const op = sent.definitions[0] as OperationDefinitionNode;
    expect(count(op.selectionSet)).toBe(180);
    expect(count(op.selectionSet)).toBeLessThanOrEqual(200);
  });

  it('reads a history page newest first, with the cursor and page size in the query string', async () => {
    const pages: unknown[] = [];
    api.history(EVENT_ID, 'Y3Vy+/').subscribe((p) => pages.push(p));
    const req = http.expectOne((r) => r.method === 'GET' && r.url === ACK_PATH);
    expect(req.request.urlWithParams).toBe(
      `${ACK_PATH}?event_id=${encodeURIComponent(EVENT_ID)}&limit=20&after=Y3Vy%2B%2F`,
    );
    req.flush({
      event_id: EVENT_ID,
      items: [{ action: 'unacknowledge', acted_at: 't2' }, ROW],
    });
    expect(pages).toEqual([
      {
        items: [
          { action: 'unacknowledge', acted_at: 't2', principal: null, note: null },
          { action: 'acknowledge', acted_at: ROW.acted_at, principal: 'operator', note: ROW.note },
        ],
        next: null,
      },
    ]);
  });
});
