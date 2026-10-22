import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { CLOCK_TICK_MS } from '../data/clock';
import { sessionActivityInterceptor } from './session-activity';
import { noticeText, SessionLimits } from './session-limits';
import { LOGOUT_PATH, SESSION_PATH, SessionService } from './session.service';

const T0 = Date.parse('2026-09-29T18:00:30Z');
const MIN = 60_000;
const unauthorized = { status: 401, statusText: 'Unauthorized' };

describe('SessionLimits', () => {
  let http: HttpTestingController;
  let session: SessionService;
  let limits: SessionLimits;

  beforeEach(() => {
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'],
    });
    vi.setSystemTime(T0);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([sessionActivityInterceptor])),
        provideHttpClientTesting(),
        { provide: CLOCK_TICK_MS, useValue: 0 },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionService);
    limits = TestBed.inject(SessionLimits);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  async function signIn(expiresAt?: string): Promise<void> {
    const done = session.load();
    http
      .expectOne(SESSION_PATH)
      .flush({ username: 'operator', ...(expiresAt ? { expires_at: expiresAt } : {}) });
    await done;
    await step(0);
  }

  async function step(ms: number): Promise<void> {
    await vi.advanceTimersByTimeAsync(ms);
    TestBed.tick();
    await vi.advanceTimersByTimeAsync(0);
  }

  /** A read answered with `status`, sent now. */
  async function request(status: number): Promise<void> {
    TestBed.inject(HttpClient)
      .get('/api/space-weather/current')
      .subscribe({ error: () => undefined });
    http.expectOne('/api/space-weather/current').flush({}, { status, statusText: 'x' });
    await step(0);
  }

  it('warns 2 minutes before the idle limit, rounded down, and speaks it once', async () => {
    await signIn();
    await step(28 * MIN - 1);
    expect(limits.notice()).toBeNull();
    expect(limits.line()).toBe('');
    await step(1);
    expect(limits.notice()).toEqual({ kind: 'idle', endsAt: T0 + 30 * MIN, failed: false });
    expect(limits.line()).toBe(
      'Session ending soon. The session can end at 18:30 UTC, 30 minutes after this page last reached the server. Use Stay signed in at the top of the page to keep it.',
    );
  });

  it('restarts the idle clock on any answer below 500 and removes the notice silently, but not on a 500', async () => {
    await signIn();
    await step(29 * MIN);
    await request(503);
    expect(limits.notice()?.kind).toBe('idle');
    TestBed.inject(HttpClient)
      .get('/api/x')
      .subscribe({ error: () => undefined });
    http.expectOne('/api/x').error(new ProgressEvent('error'));
    await step(0);
    expect(limits.notice()?.kind).toBe('idle');
    await request(404);
    expect(limits.notice()).toBeNull();
    expect(limits.line()).toBe('');
    await step(27 * MIN);
    expect(limits.notice()).toBeNull();
  });

  it('Stay signed in checks the session, keeps its line, and asks the header to focus the signed in line', async () => {
    await signIn();
    await step(28 * MIN);
    const staying = limits.stay();
    expect(limits.staying()).toBe(true);
    expect(limits.line()).toBe('Checking the session.');
    void limits.stay();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    await staying;
    await step(0);
    expect(limits.notice()).toBeNull();
    expect(limits.staying()).toBe(false);
    expect(limits.line()).toBe('You are still signed in.');
    expect(session.focus()).toMatchObject({ target: 'who' });
  });

  it('keeps the notice, as not confirmed, when Stay signed in gets no usable answer, and ends the session on 401', async () => {
    await signIn();
    await step(28 * MIN);
    let staying = limits.stay();
    http.expectOne(SESSION_PATH).flush({}, { status: 503, statusText: 'x' });
    await staying;
    await step(0);
    expect(limits.notice()).toEqual({ kind: 'idle', endsAt: T0 + 30 * MIN, failed: true });
    expect(limits.line()).toBe(
      'Not confirmed. The server could not be reached or had a problem, so the session can still end at 18:30 UTC. Use Stay signed in to try again.',
    );
    staying = limits.stay();
    http.expectOne(SESSION_PATH).flush({}, unauthorized);
    await staying;
    await step(0);
    expect(session.state()).toEqual({ kind: 'ended' });
    expect(limits.notice()).toBeNull();
    expect(limits.line()).toBe('');
  });

  it('gives the 8 hour notice 10 minutes ahead, with no Stay signed in, when that end comes first', async () => {
    await signIn('2026-09-29T18:20:45.000000Z');
    await step(10 * MIN + 14_999);
    expect(limits.notice()).toBeNull();
    await step(1);
    expect(limits.notice()).toEqual({ kind: 'end', endsAt: Date.parse('2026-09-29T18:20:45Z') });
    expect(noticeText(limits.notice()!)).toBe(
      'Session ends at 18:20 UTC. A session lasts at most 8 hours from sign in and cannot be extended. To keep acknowledging after that, sign in again.',
    );
  });

  it('lets the 8 hour notice take over once the idle limit moves past that end', async () => {
    await signIn('2026-09-29T18:45:30.000000Z');
    await step(28 * MIN);
    expect(limits.notice()?.kind).toBe('idle');
    await request(200);
    expect(limits.notice()).toBeNull();
    await step(7 * MIN);
    expect(limits.notice()?.kind).toBe('end');
  });

  it('checks the session once, a minute after a limit passed with no answered request, and ends it on 401', async () => {
    await signIn();
    await step(31 * MIN - 1);
    http.expectNone(SESSION_PATH);
    await step(1);
    http.expectOne(SESSION_PATH).flush({}, unauthorized);
    await step(0);
    expect(session.state()).toEqual({ kind: 'ended' });
    expect(session.focus()).toMatchObject({ target: 'sign_in', onlyIfLost: true });
    await step(10 * MIN);
    http.expectNone(SESSION_PATH);
  });

  it('after a limit passed, a live session the check finds removes the notice', async () => {
    await signIn();
    await step(31 * MIN);
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    await step(0);
    expect(limits.notice()).toBeNull();
    expect(session.state()).toEqual({ kind: 'signed_in', username: 'operator' });
  });

  it('clears the notice as soon as Sign out is pressed, and brings it back if the sign out fails', async () => {
    await signIn();
    await step(28 * MIN);
    const out = session.signOut();
    TestBed.tick();
    expect(limits.notice()).toBeNull();
    http.expectOne(LOGOUT_PATH).flush({}, { status: 503, statusText: 'x' });
    await out;
    await step(0);
    expect(limits.notice()?.kind).toBe('idle');
  });

  it('shows nothing while signed out or while the sign in state is unknown', async () => {
    const done = session.load();
    http.expectOne(SESSION_PATH).flush({}, { status: 503, statusText: 'x' });
    await done;
    await step(40 * MIN);
    http.match(SESSION_PATH).forEach((r) => r.cancelled || r.flush({}, unauthorized));
    expect(limits.notice()).toBeNull();
    expect(limits.line()).toBe('');
  });
});
