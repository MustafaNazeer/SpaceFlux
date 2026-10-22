import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { KeptNotes } from '../ack/kept-notes';
import { OperatorFields } from '../ack/operator-fields';
import {
  LOGIN_PATH,
  LOGOUT_PATH,
  expiryOf,
  retryAfterSeconds,
  SESSION_PATH,
  SessionService,
  SignInResult,
} from './session.service';

describe('SessionService', () => {
  let http: HttpTestingController;
  let service: SessionService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(SessionService);
  });

  afterEach(() => http.verify());

  it('reads the signed in username', async () => {
    const done = service.load();
    http.expectOne({ method: 'GET', url: SESSION_PATH }).flush({ username: 'operator' });
    await done;
    expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
  });

  it('reads 401 as anonymous, the normal state for a reader', async () => {
    const done = service.load();
    http
      .expectOne(SESSION_PATH)
      .flush(
        { title: 'Unauthorized', status: 401, detail: 'Unauthorized.' },
        { status: 401, statusText: 'Unauthorized' },
      );
    await done;
    expect(service.state()).toEqual({ kind: 'anonymous' });
  });

  it('reads any other failure as unknown rather than as signed out', async () => {
    const done = service.load();
    http.expectOne(SESSION_PATH).error(new ProgressEvent('error'));
    await done;
    expect(service.state()).toEqual({ kind: 'unknown' });
  });

  describe('when the API does not answer', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('shows the anonymous read only view after 10 s and asks again later', async () => {
      const done = service.load();
      const first = http.expectOne(SESSION_PATH);
      await vi.advanceTimersByTimeAsync(9_999);
      expect(service.state()).toEqual({ kind: 'checking' });
      await vi.advanceTimersByTimeAsync(1);
      await done;
      expect(first.cancelled).toBe(true);
      expect(service.state()).toEqual({ kind: 'anonymous' });

      await vi.advanceTimersByTimeAsync(30_000);
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      await vi.advanceTimersByTimeAsync(0);
      expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
    });

    it('stops retrying once the API answers', async () => {
      const done = service.load();
      http.expectOne(SESSION_PATH);
      await vi.advanceTimersByTimeAsync(10_000);
      await done;
      await vi.advanceTimersByTimeAsync(30_000);
      http.expectOne(SESSION_PATH).flush({}, { status: 401, statusText: 'Unauthorized' });
      await vi.advanceTimersByTimeAsync(0);
      expect(service.state()).toEqual({ kind: 'anonymous' });
      await vi.advanceTimersByTimeAsync(120_000);
      http.expectNone(SESSION_PATH);
    });

    it('retries after a server error or a network error, as after a timeout', async () => {
      const done = service.load();
      http.expectOne(SESSION_PATH).flush({}, { status: 503, statusText: 'Service Unavailable' });
      await done;
      expect(service.state()).toEqual({ kind: 'unknown' });
      await vi.advanceTimersByTimeAsync(30_000);
      http.expectOne(SESSION_PATH).error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(0);
      expect(service.state()).toEqual({ kind: 'unknown' });
      await vi.advanceTimersByTimeAsync(30_000);
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      await vi.advanceTimersByTimeAsync(0);
      expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
    });
  });
});

const unauthorized = { status: 401, statusText: 'Unauthorized' };
const problem = (status: number) => ({ title: 'x', status });

describe('SessionService sign in and sign out', () => {
  let http: HttpTestingController;
  let service: SessionService;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=tok-1; path=/';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
        ),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(SessionService);
  });

  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  async function startAs(username: string | null): Promise<void> {
    const done = service.load();
    if (username === null) {
      http.expectOne(SESSION_PATH).flush(problem(401), unauthorized);
    } else {
      http.expectOne(SESSION_PATH).flush({ username });
    }
    await done;
  }

  /** Lets the promise chain reach its next request. */
  const tick = () => new Promise((r) => setTimeout(r));

  it('posts the credentials form encoded with the XSRF header, writing + and & so the server reads them back', async () => {
    await startAs(null);
    const result = service.signIn('oper ator', 'p+ss&w=rd \u{1F6F0}');
    const req = http.expectOne({ method: 'POST', url: LOGIN_PATH });
    expect(req.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('tok-1');
    expect(req.request.urlWithParams).toBe(LOGIN_PATH);
    const read = new URLSearchParams(req.request.body as string);
    expect(read.get('username')).toBe('oper ator');
    expect(read.get('password')).toBe('p+ss&w=rd \u{1F6F0}');
    expect(req.request.body).toContain('p%2Bss%26w%3Drd');
    req.flush('', { status: 204, statusText: 'No Content' });
    await tick();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    expect(await result).toEqual({ kind: 'signed_in' });
  });

  it('on success asks the session for the name, says who is signed in, moves focus to Sign out and tells listeners', async () => {
    await startAs(null);
    const listener = vi.fn();
    service.onSignedIn(listener);
    const result = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush('', { status: 204, statusText: 'No Content' });
    await tick();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    await result;
    expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
    expect(service.line()).toEqual({ lead: '', text: 'Signed in as operator.', failure: false });
    expect(service.focus()).toMatchObject({ target: 'sign_out', onlyIfLost: false });
    expect(listener).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(OperatorFields).hidden()).toBe(false);
    expect(service.expiry()).toBeNull();
  });

  it('reads 401 as wrong credentials, the same answer as a server with no operator, and keeps the state', async () => {
    await startAs(null);
    const result = service.signIn('operator', 'bad');
    http.expectOne(LOGIN_PATH).flush(problem(401), unauthorized);
    expect(await result).toEqual({ kind: 'wrong' });
    expect(service.state()).toEqual({ kind: 'anonymous' });
    http.expectNone(SESSION_PATH);
  });

  it.each([
    ['120', { kind: 'backoff', seconds: 120 }],
    ['1', { kind: 'backoff', seconds: 1 }],
    [null, { kind: 'backoff_later' }],
    ['soon', { kind: 'backoff_later' }],
    ['0', { kind: 'backoff_later' }],
    ['Wed, 21 Oct 2026 07:28:00 GMT', { kind: 'backoff_later' }],
  ])('reads 429 with Retry-After %s', async (header, expected) => {
    await startAs(null);
    const result = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush(problem(429), {
      status: 429,
      statusText: 'Too Many Requests',
      headers: header === null ? {} : { 'Retry-After': header },
    });
    expect(await result).toEqual(expected as SignInResult);
  });

  it('reads 400 as a refused request and checks nothing else', async () => {
    await startAs(null);
    const result = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush(problem(400), { status: 400, statusText: 'x' });
    expect(await result).toEqual({ kind: 'refused' });
    http.expectNone(SESSION_PATH);
  });

  it.each([
    ['a server error', (r: TestRequest) => r.flush(problem(503), { status: 503, statusText: 'x' })],
    ['a 500', (r: TestRequest) => r.flush(problem(500), { status: 500, statusText: 'x' })],
    ['a lost connection', (r: TestRequest) => r.error(new ProgressEvent('error'))],
  ])('after %s checks the session once instead of resending: no session found', async (_, fail) => {
    await startAs(null);
    const result = service.signIn('operator', 'pw');
    fail(http.expectOne(LOGIN_PATH));
    await tick();
    http.expectOne(SESSION_PATH).flush(problem(401), unauthorized);
    expect(await result).toEqual({ kind: 'no_session' });
    expect(service.state()).toEqual({ kind: 'anonymous' });
    http.expectNone(LOGIN_PATH);
  });

  it('after a server error follows a live session the check finds', async () => {
    await startAs(null);
    const result = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush(problem(502), { status: 502, statusText: 'x' });
    await tick();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    expect(await result).toEqual({ kind: 'signed_in' });
    expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
  });

  it('after an unknown outcome whose check fails too, reads the state as unknown and checks again later', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      await startAs(null);
      const result = service.signIn('operator', 'pw');
      http.expectOne(LOGIN_PATH).error(new ProgressEvent('error'));
      await vi.advanceTimersByTimeAsync(0);
      http.expectOne(SESSION_PATH).error(new ProgressEvent('error'));
      expect(await result).toEqual({ kind: 'not_confirmed' });
      expect(service.state()).toEqual({ kind: 'unknown' });
      await vi.advanceTimersByTimeAsync(30_000);
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      await vi.advanceTimersByTimeAsync(0);
      expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('after a 403 checks the session exactly once, never resends the login, and reads a refusal on 401', async () => {
    await startAs(null);
    const result = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await tick();
    http.expectOne(SESSION_PATH).flush(problem(401), unauthorized);
    expect(await result).toEqual({ kind: 'refused' });
    expect(service.state()).toEqual({ kind: 'anonymous' });
    http.expectNone(LOGIN_PATH);
    http.expectNone(SESSION_PATH);
  });

  it('after a 403 whose check fails, still reads a refusal, since a 403 is answered before anything is written', async () => {
    await startAs(null);
    const result = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await tick();
    http.expectOne(SESSION_PATH).error(new ProgressEvent('error'));
    expect(await result).toEqual({ kind: 'refused' });
  });

  it('after a 403 whose session check finds a session, shows it as signed in', async () => {
    await startAs(null);
    const result = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await tick();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    expect(await result).toEqual({ kind: 'signed_in' });
    expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
    http.expectNone(LOGIN_PATH);
  });

  it('keeps the ended view after a 403 whose check answers 401', async () => {
    await startAs('operator');
    service.end('ack');
    const result = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush(problem(403), { status: 403, statusText: 'Forbidden' });
    await tick();
    http.expectOne(SESSION_PATH).flush(problem(401), unauthorized);
    await result;
    expect(service.state()).toEqual({ kind: 'ended' });
  });

  it('reads a 204 whose session check fails as not confirmed', async () => {
    await startAs(null);
    const result = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush('', { status: 204, statusText: 'No Content' });
    await tick();
    http.expectOne(SESSION_PATH).error(new ProgressEvent('error'));
    expect(await result).toEqual({ kind: 'not_confirmed' });
    expect(service.state()).toEqual({ kind: 'unknown' });
  });

  it('keeps a backoff with a usable Retry-After until its time, outside any form', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    vi.setSystemTime(new Date('2026-09-29T18:00:10Z'));
    try {
      await startAs(null);
      const result = service.signIn('operator', 'pw');
      http.expectOne(LOGIN_PATH).flush(problem(429), {
        status: 429,
        statusText: 'x',
        headers: { 'Retry-After': '120' },
      });
      await result;
      expect(service.backoff()).toEqual({
        until: Date.parse('2026-09-29T18:02:10Z'),
        seconds: 120,
      });
      await vi.advanceTimersByTimeAsync(119_999);
      expect(service.backoff()).not.toBeNull();
      await vi.advanceTimersByTimeAsync(1);
      expect(service.backoff()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  describe('when the API does not answer', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('waits 35 s, then checks the session instead of resending, and follows a live session', async () => {
      await startAs(null);
      const result = service.signIn('operator', 'pw');
      const login = http.expectOne(LOGIN_PATH);
      await vi.advanceTimersByTimeAsync(34_999);
      http.expectNone(SESSION_PATH);
      await vi.advanceTimersByTimeAsync(1);
      expect(login.cancelled).toBe(true);
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      expect(await result).toEqual({ kind: 'signed_in' });
      expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
      http.expectNone(LOGIN_PATH);
    });

    it('after a login timeout with no session, says no session was found', async () => {
      await startAs(null);
      const result = service.signIn('operator', 'pw');
      http.expectOne(LOGIN_PATH);
      await vi.advanceTimersByTimeAsync(35_000);
      http.expectOne(SESSION_PATH).flush(problem(401), unauthorized);
      expect(await result).toEqual({ kind: 'no_session' });
      expect(service.state()).toEqual({ kind: 'anonymous' });
    });

    it('fails a sign out that gets no answer in 35 s and keeps the signed in view', async () => {
      await startAs('operator');
      const done = service.signOut();
      http.expectOne(LOGOUT_PATH);
      await vi.advanceTimersByTimeAsync(35_000);
      await done;
      expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
      expect(service.line().failure).toBe(true);
    });
  });

  it('signs out with the XSRF header, clears operator fields at once, says so and moves focus to Sign in', async () => {
    await startAs('operator');
    const cleared = vi.fn();
    service.onOperatorCleared(cleared);
    const done = service.signOut();
    expect(service.line()).toEqual({ lead: '', text: 'Signing out.', failure: false });
    expect(service.signingOut()).toBe(true);
    const req = http.expectOne({ method: 'POST', url: LOGOUT_PATH });
    expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('tok-1');
    // A second press while the first is out sends nothing.
    void service.signOut();
    http.expectNone(LOGOUT_PATH);
    req.flush('', { status: 204, statusText: 'No Content' });
    await done;
    expect(cleared).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(OperatorFields).hidden()).toBe(true);
    expect(service.state()).toEqual({ kind: 'anonymous' });
    expect(service.line()).toEqual({ lead: '', text: 'Signed out.', failure: false });
    expect(service.focus()).toMatchObject({ target: 'sign_in', onlyIfLost: false });
    expect(service.signingOut()).toBe(false);
  });

  it('keeps the signed in view when sign out fails, and says it is not confirmed', async () => {
    await startAs('operator');
    const cleared = vi.fn();
    service.onOperatorCleared(cleared);
    const done = service.signOut();
    http.expectOne(LOGOUT_PATH).flush(problem(500), { status: 500, statusText: 'x' });
    await done;
    expect(cleared).not.toHaveBeenCalled();
    expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
    expect(service.line()).toEqual({
      lead: 'Sign out not confirmed.',
      text: 'The server could not be reached or had a problem, so you may still be signed in. Use Sign out to try again.',
      failure: true,
    });
  });

  it('ends a session refused on acknowledgement: clears, reads ended, and moves focus to Sign in', async () => {
    await startAs('operator');
    const cleared = vi.fn();
    service.onOperatorCleared(cleared);
    service.end('ack');
    expect(cleared).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(OperatorFields).hidden()).toBe(true);
    expect(service.state()).toEqual({ kind: 'ended' });
    expect(service.line().text).toBe(
      'Your session has ended, so the acknowledgement was not saved. Sign in again to acknowledge.',
    );
    expect(service.focus()).toMatchObject({ target: 'sign_in', onlyIfLost: false });
  });

  it('the minute check ends a session the API no longer knows, moving focus only if it was lost', async () => {
    await startAs('operator');
    const cleared = vi.fn();
    service.onOperatorCleared(cleared);
    const done = service.check();
    // A second check while one is out sends nothing.
    void service.check();
    http.expectOne(SESSION_PATH).flush(problem(401), unauthorized);
    await done;
    expect(cleared).toHaveBeenCalledTimes(1);
    expect(service.state()).toEqual({ kind: 'ended' });
    expect(service.line().text).toBe('Your session has ended. Sign in again to acknowledge.');
    expect(service.focus()).toMatchObject({ target: 'sign_in', onlyIfLost: true });
    // Once ended, the check sends nothing.
    await service.check();
    http.expectNone(SESSION_PATH);
  });

  it('the minute check changes nothing on a good answer or a failure, and sends nothing when signed out', async () => {
    await startAs('operator');
    let done = service.check();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    await done;
    done = service.check();
    http.expectOne(SESSION_PATH).flush(problem(503), { status: 503, statusText: 'x' });
    await done;
    expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
    service.end('ack');
    await service.check();
    http.expectNone(SESSION_PATH);
  });

  it('reads Retry-After as whole seconds from 1 up', () => {
    expect(retryAfterSeconds('5')).toBe(5);
    expect(retryAfterSeconds(' 7 ')).toBe(7);
    expect(retryAfterSeconds('0')).toBeNull();
    expect(retryAfterSeconds('-1')).toBeNull();
    expect(retryAfterSeconds('1.5')).toBeNull();
    expect(retryAfterSeconds(null)).toBeNull();
  });

  it('ignores a startup check that answers after a sign in, so the newer state stands', async () => {
    const startup = service.load();
    const first = http.expectOne(SESSION_PATH);
    const signing = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush('', { status: 204, statusText: 'No Content' });
    await tick();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    await signing;
    first.flush(problem(401), unauthorized);
    await startup;
    expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
  });

  it('ignores a minute check that answers 401 after a sign out and a new sign in', async () => {
    await startAs('operator');
    const checked = service.check();
    const stale = http.expectOne(SESSION_PATH);
    const out = service.signOut();
    http.expectOne(LOGOUT_PATH).flush('', { status: 204, statusText: 'No Content' });
    await out;
    const signing = service.signIn('operator', 'pw');
    http.expectOne(LOGIN_PATH).flush('', { status: 204, statusText: 'No Content' });
    await tick();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    await signing;
    stale.flush(problem(401), unauthorized);
    await checked;
    expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
  });

  it('reads the 8 hour end from the session answer, placed on this clock by the Date header', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-09-29T18:00:00Z'));
    try {
      const done = service.load();
      // The server's clock reads 18:05, five minutes ahead of this one.
      http
        .expectOne(SESSION_PATH)
        .flush(
          { username: 'operator', expires_at: '2026-09-30T01:05:00.123456Z' },
          { status: 200, statusText: 'OK', headers: { Date: 'Tue, 29 Sep 2026 18:05:00 GMT' } },
        );
      await done;
      expect(service.expiry()).toEqual({
        at: Date.parse('2026-09-30T01:05:00.123Z'),
        localAt: Date.parse('2026-09-30T01:00:00.123Z'),
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it('trusts this clock when the Date header is missing, and has no end without expires_at', () => {
    expect(expiryOf({ username: 'o', expires_at: '2026-09-30T01:05:00Z' }, null, 0)).toEqual({
      at: Date.parse('2026-09-30T01:05:00Z'),
      localAt: Date.parse('2026-09-30T01:05:00Z'),
    });
    expect(expiryOf({ username: 'o' }, 'Tue, 29 Sep 2026 18:05:00 GMT', 0)).toBeNull();
  });

  it('says when a note being written was kept as the session ended, one or several', async () => {
    await startAs('operator');
    const kept = TestBed.inject(KeptNotes);
    const stop = service.onOperatorCleared((reason) => {
      if (reason === 'ended') {
        kept.set('a', 'one');
      }
    });
    service.end('check');
    expect(service.line().text).toBe(
      'Your session has ended. Sign in again to acknowledge. The note you were writing is kept, not saved.',
    );
    stop();
    await startAs('operator');
    service.onOperatorCleared(() => {
      kept.set('b', 'two');
      kept.set('c', 'three');
    });
    service.end('ack');
    expect(service.line().text).toBe(
      'Your session has ended, so the acknowledgement was not saved. Sign in again to acknowledge. The notes you were writing are kept, not saved.',
    );
  });

  it('tells listeners a sign out is a sign out, and drops every kept note with it', async () => {
    await startAs('operator');
    const kept = TestBed.inject(KeptNotes);
    kept.set('a', 'note');
    const reasons: string[] = [];
    service.onOperatorCleared((r) => reasons.push(r));
    const out = service.signOut();
    http.expectOne(LOGOUT_PATH).flush('', { status: 204, statusText: 'No Content' });
    await out;
    expect(reasons).toEqual(['sign_out']);
    expect(kept.get('a')).toBeUndefined();
  });
});
