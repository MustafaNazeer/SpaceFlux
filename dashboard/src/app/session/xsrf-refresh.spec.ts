import {
  HttpClient,
  HttpErrorResponse,
  HttpHandlerFn,
  HttpRequest,
  provideHttpClient,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Apollo, gql, provideApollo } from 'apollo-angular';
import { EMPTY, firstValueFrom } from 'rxjs';

import { ACK_PATH, AckApi } from '../ack/ack-api';
import { apolloOptions, GRAPHQL_PATH } from '../data/graphql';
import { EVENT_ID } from '../../testing/acks';
import { provideFakeSocket } from '../../testing/fake-socket';
import {
  LOGIN_PATH,
  LOGOUT_PATH,
  SESSION_PATH,
  SESSION_TIMEOUT_MS,
  SessionService,
} from './session.service';
import {
  provideApiHttpClient,
  XSRF_COOKIE,
  XSRF_HEADER,
  xsrfRefreshInterceptor,
} from './xsrf-refresh';

function readXsrf(): string | null {
  const pair = document.cookie.split('; ').find((c) => c.startsWith(`${XSRF_COOKIE}=`));
  return pair === undefined ? null : pair.slice(XSRF_COOKIE.length + 1) || null;
}

const writeXsrf = (value: string) => (document.cookie = `${XSRF_COOKIE}=${value}; path=/`);
const expireXsrf = () =>
  (document.cookie = `${XSRF_COOKIE}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`);

const tick = async (n = 4) => {
  for (let i = 0; i < n; i++) {
    await new Promise((r) => setTimeout(r));
  }
};

const unauthorized = { status: 401, statusText: 'Unauthorized' };
const forbidden = { status: 403, statusText: 'Forbidden' };

/** Settles a promise into its value or its error, so a test can assert on either. */
const settle = <T>(p: Promise<T>) =>
  p.then(
    (value) => ({ value, error: null as unknown }),
    (error: unknown) => ({ value: undefined, error }),
  );

describe('xsrfRefreshInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;

  function setUp(timeoutMs = 10_000) {
    TestBed.configureTestingModule({
      providers: [
        provideApiHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_TIMEOUT_MS, useValue: timeoutMs },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  }

  /** The session answer the API gives a request without the cookie: 401 signed out, with a fresh token. */
  function answerSession(req: TestRequest, token: string): void {
    writeXsrf(token);
    req.flush({ title: 'Unauthorized', status: 401 }, unauthorized);
  }

  beforeEach(() => expireXsrf());

  afterEach(() => {
    http.verify();
    expireXsrf();
  });

  it('fetches the session once before an unsafe request that has no token, then sends it with the new token', async () => {
    setUp();
    const sent = settle(firstValueFrom(client.post(LOGIN_PATH, 'a=b', { responseType: 'text' })));
    await tick();

    expect(http.match(LOGIN_PATH)).toEqual([]);
    const session = http.expectOne(SESSION_PATH);
    expect(session.request.method).toBe('GET');
    answerSession(session, 'tok-new');
    await tick();

    const login = http.expectOne(LOGIN_PATH);
    expect(login.request.method).toBe('POST');
    expect(login.request.headers.get(XSRF_HEADER)).toBe('tok-new');
    login.flush('', { status: 204, statusText: 'No Content' });
    expect((await sent).error).toBeNull();
    http.expectNone(SESSION_PATH);
  });

  it('sends an unsafe request that has a token straight through, with Angular adding the header', async () => {
    setUp();
    writeXsrf('tok-1');
    const sent = settle(firstValueFrom(client.post(ACK_PATH, {})));
    await tick();

    http.expectNone(SESSION_PATH);
    const ack = http.expectOne(ACK_PATH);
    expect(ack.request.headers.get(XSRF_HEADER)).toBe('tok-1');
    ack.flush({});
    expect((await sent).error).toBeNull();
  });

  it.each(['PUT', 'PATCH', 'DELETE'])('treats %s as unsafe', async (method) => {
    setUp();
    const sent = settle(firstValueFrom(client.request(method, ACK_PATH, { body: {} })));
    await tick();

    expect(http.match(ACK_PATH)).toEqual([]);
    answerSession(http.expectOne(SESSION_PATH), 'tok-2');
    await tick();
    const req = http.expectOne(ACK_PATH);
    expect(req.request.method).toBe(method);
    expect(req.request.headers.get(XSRF_HEADER)).toBe('tok-2');
    req.flush({});
    await sent;
  });

  it.each(['GET', 'HEAD', 'OPTIONS', 'TRACE'])(
    'leaves a %s without a token alone',
    async (method) => {
      setUp();
      const sent = settle(firstValueFrom(client.request(method, '/api/watchlist')));
      await tick();

      http.expectNone(SESSION_PATH);
      const req = http.expectOne('/api/watchlist');
      expect(req.request.method).toBe(method);
      req.flush({});
      await sent;
    },
  );

  it('shares one session fetch among unsafe requests sent while it is out', async () => {
    setUp();
    const first = settle(firstValueFrom(client.post(LOGIN_PATH, 'a=b', { responseType: 'text' })));
    const second = settle(firstValueFrom(client.post(GRAPHQL_PATH, { query: '{ __typename }' })));
    const third = settle(firstValueFrom(client.post(ACK_PATH, {})));
    await tick();

    const sessions = http.match(SESSION_PATH);
    expect(sessions.length).toBe(1);
    answerSession(sessions[0], 'tok-3');
    await tick();

    for (const path of [LOGIN_PATH, GRAPHQL_PATH, ACK_PATH]) {
      const req = http.expectOne(path);
      expect(req.request.headers.get(XSRF_HEADER), path).toBe('tok-3');
      req.flush(path === LOGIN_PATH ? '' : {});
    }
    await Promise.all([first, second, third]);
    http.expectNone(SESSION_PATH);
  });

  it('fetches again for a later request once the earlier fetch has settled and the token is gone again', async () => {
    setUp();
    const first = settle(firstValueFrom(client.post(ACK_PATH, {})));
    await tick();
    answerSession(http.expectOne(SESSION_PATH), 'tok-4');
    await tick();
    http.expectOne(ACK_PATH).flush({});
    await first;

    expireXsrf();
    const second = settle(firstValueFrom(client.post(ACK_PATH, {})));
    await tick();
    answerSession(http.expectOne(SESSION_PATH), 'tok-5');
    await tick();
    const ack = http.expectOne(ACK_PATH);
    expect(ack.request.headers.get(XSRF_HEADER)).toBe('tok-5');
    ack.flush({});
    await second;
  });

  it('leaves a cross origin request alone', async () => {
    setUp();
    const url = 'https://elsewhere.example/api/auth/login';
    const sent = settle(firstValueFrom(client.post(url, {})));
    await tick();

    http.expectNone(SESSION_PATH);
    const req = http.expectOne(url);
    expect(req.request.headers.has(XSRF_HEADER)).toBe(false);
    req.flush({});
    await sent;
  });

  it('leaves a same origin request outside the API alone', async () => {
    setUp();
    const sent = settle(firstValueFrom(client.post('/elsewhere', {})));
    await tick();

    http.expectNone(SESSION_PATH);
    http.expectOne('/elsewhere').flush({});
    await sent;
  });

  it('never resends the request when the API still refuses it after the fetch', async () => {
    setUp();
    const sent = settle(firstValueFrom(client.post(LOGIN_PATH, 'a=b', { responseType: 'text' })));
    await tick();
    answerSession(http.expectOne(SESSION_PATH), 'tok-6');
    await tick();
    http.expectOne(LOGIN_PATH).flush({ status: 403 }, forbidden);

    const { error } = await sent;
    expect((error as HttpErrorResponse).status).toBe(403);
    await tick();
    http.expectNone(LOGIN_PATH);
    http.expectNone(SESSION_PATH);
  });

  it('sends the request without a token, as before, when the session fetch fails on the network', async () => {
    setUp();
    const sent = settle(firstValueFrom(client.post(LOGIN_PATH, 'a=b', { responseType: 'text' })));
    await tick();
    http.expectOne(SESSION_PATH).error(new ProgressEvent('error'));
    await tick();

    const login = http.expectOne(LOGIN_PATH);
    expect(login.request.headers.has(XSRF_HEADER)).toBe(false);
    login.flush({ status: 403 }, forbidden);
    expect(((await sent).error as HttpErrorResponse).status).toBe(403);
  });

  it('starts a new fetch when every request waiting on the shared one was given up', async () => {
    setUp();
    const given = client.post(ACK_PATH, {}).subscribe({ error: () => undefined });
    await tick();
    const first = http.expectOne(SESSION_PATH);
    given.unsubscribe();
    expect(first.cancelled).toBe(true);
    http.expectNone(ACK_PATH);

    const sent = settle(firstValueFrom(client.post(ACK_PATH, {})));
    await tick();
    const sessions = http.match(SESSION_PATH);
    expect(sessions.length).toBe(1);
    answerSession(sessions[0], 'tok-8');
    await tick();

    const acks = http.match(ACK_PATH);
    expect(acks.length).toBe(1);
    expect(acks[0].request.headers.get(XSRF_HEADER)).toBe('tok-8');
    acks[0].flush({});
    expect((await sent).error).toBeNull();
  });

  it('sends the request, and fetches again next time, when the session fetch ends without an answer', async () => {
    const sessionFetches: string[] = [];
    // Stands in for a handler further down that completes the session GET without a response.
    const endsEmpty = (req: HttpRequest<unknown>, next: HttpHandlerFn) => {
      if (req.url === SESSION_PATH) {
        sessionFetches.push(req.method);
        return EMPTY;
      }
      return next(req);
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withXsrfConfiguration({ cookieName: XSRF_COOKIE, headerName: XSRF_HEADER }),
          withInterceptors([xsrfRefreshInterceptor, endsEmpty]),
        ),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);

    for (let round = 1; round <= 2; round++) {
      const sent = settle(firstValueFrom(client.post(ACK_PATH, {})));
      await tick();
      const ack = http.expectOne(ACK_PATH);
      expect(ack.request.headers.has(XSRF_HEADER)).toBe(false);
      ack.flush({});
      expect((await sent).error).toBeNull();
      expect(sessionFetches.length).toBe(round);
    }
  });

  it('sends the request anyway when the session fetch gets no answer in time', async () => {
    setUp(30);
    const sent = settle(firstValueFrom(client.post(ACK_PATH, {})));
    await tick();
    const session = http.expectOne(SESSION_PATH);
    await new Promise((r) => setTimeout(r, 60));
    await tick();

    expect(session.cancelled).toBe(true);
    const ack = http.expectOne(ACK_PATH);
    expect(ack.request.headers.has(XSRF_HEADER)).toBe(false);
    ack.flush({});
    await sent;
  });

  it('sends the request with the new token when the session answers 200 signed in', async () => {
    setUp();
    const sent = settle(firstValueFrom(client.post(ACK_PATH, {})));
    await tick();
    const session = http.expectOne(SESSION_PATH);
    writeXsrf('tok-7');
    session.flush({ username: 'operator' });
    await tick();
    const ack = http.expectOne(ACK_PATH);
    expect(ack.request.headers.get(XSRF_HEADER)).toBe('tok-7');
    ack.flush({});
    expect((await sent).error).toBeNull();
  });
});

/**
 * The API's CSRF rules over plain HTTP, as in sign-in-again.spec.ts, plus the acknowledgement and GraphQL endpoints:
 * a request without the cookie gets one, an unsafe request without the matching header is refused 403, a login
 * reissues the cookie and a logout only expires it.
 */
class FakeApi {
  signedIn = false;
  private issued = 0;
  readonly seen: string[] = [];

  answer(req: TestRequest): void {
    const { method } = req.request;
    const url = req.request.url;
    let token = readXsrf();
    if (token === null) {
      token = `tok-${++this.issued}`;
      writeXsrf(token);
    }
    const reply = (status: number, body: Parameters<TestRequest['flush']>[0] = '') => {
      this.seen.push(`${method} ${url} ${status}`);
      req.flush(body, { status, statusText: String(status) });
    };
    if (method === 'POST' && req.request.headers.get(XSRF_HEADER) !== token) {
      reply(403, { title: 'Forbidden', status: 403 });
    } else if (method === 'POST' && url === LOGIN_PATH) {
      this.signedIn = true;
      writeXsrf(`tok-${++this.issued}`);
      reply(204);
    } else if (method === 'POST' && url === LOGOUT_PATH) {
      this.signedIn = false;
      expireXsrf();
      reply(204);
    } else if (method === 'GET' && url === SESSION_PATH) {
      if (this.signedIn) {
        reply(200, { username: 'operator' });
      } else {
        reply(401, { title: 'Unauthorized', status: 401 });
      }
    } else if (method === 'POST' && url === ACK_PATH) {
      if (this.signedIn) {
        reply(200, {
          event_id: EVENT_ID,
          action: 'acknowledge',
          principal: 'operator',
          acted_at: '2026-10-22T10:15:00.123456Z',
          note: null,
        });
      } else {
        reply(401, { title: 'Unauthorized', status: 401 });
      }
    } else if (method === 'POST' && url === GRAPHQL_PATH) {
      reply(200, { data: { __typename: 'Query' } });
    } else {
      throw new Error(`unexpected request ${method} ${url}`);
    }
  }
}

describe('Requests after a sign out on the same page', () => {
  let http: HttpTestingController;
  let session: SessionService;
  let api: FakeApi;

  beforeEach(() => {
    expireXsrf();
    TestBed.configureTestingModule({
      providers: [
        provideApiHttpClient(),
        provideHttpClientTesting(),
        provideApollo(apolloOptions),
        provideFakeSocket(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionService);
    api = new FakeApi();
  });

  afterEach(() => {
    http.verify();
    expireXsrf();
  });

  async function served<T>(work: Promise<T>): Promise<T> {
    let settled = false;
    const result = work.finally(() => (settled = true));
    for (let i = 0; i < 50 && !settled; i++) {
      http.match(() => true).forEach((req) => api.answer(req));
      await new Promise((r) => setTimeout(r));
    }
    return result;
  }

  async function signInThenOut(): Promise<void> {
    await served(session.load());
    expect(await served(session.signIn('operator', 'pw'))).toEqual({ kind: 'signed_in' });
    await served(session.signOut());
    expect(readXsrf()).toBeNull();
  }

  it('answers the GraphQL poll instead of refusing it', async () => {
    await signInThenOut();
    const apollo = TestBed.inject(Apollo);

    const result = await served(
      firstValueFrom(apollo.query({ query: gql('{ __typename }'), fetchPolicy: 'network-only' })),
    );

    expect(result.data).toEqual({ __typename: 'Query' });
    expect(
      api.seen.filter((s) => s.endsWith(' 403')),
      api.seen.join(', '),
    ).toEqual([]);
  });

  it('answers an acknowledgement as a session that has ended, never as a refused request', async () => {
    await signInThenOut();

    const result = await served(TestBed.inject(AckApi).act(EVENT_ID, 'acknowledge'));

    expect(result).toEqual({ kind: 'ended' });
    expect(
      api.seen.filter((s) => s.endsWith(' 403')),
      api.seen.join(', '),
    ).toEqual([]);
    expect(api.seen).toContain(`POST ${ACK_PATH} 401`);
  });

  it('saves an acknowledgement sent while signed in after the cookie was cleared, as another tab can', async () => {
    await served(session.load());
    expect(await served(session.signIn('operator', 'pw'))).toEqual({ kind: 'signed_in' });
    expireXsrf();

    const result = await served(TestBed.inject(AckApi).act(EVENT_ID, 'acknowledge'));

    expect(result.kind, api.seen.join(', ')).toBe('saved');
    expect(api.seen.filter((s) => s.endsWith(' 403'))).toEqual([]);
  });
});
