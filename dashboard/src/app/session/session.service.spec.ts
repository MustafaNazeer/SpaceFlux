import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SESSION_PATH, SessionService } from './session.service';

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
