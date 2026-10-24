import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { LOGIN_PATH, LOGOUT_PATH, SESSION_PATH, SessionService } from './session.service';
import { provideApiHttpClient } from './xsrf-refresh';

const XSRF = 'XSRF-TOKEN';

function readXsrf(): string | null {
  const pair = document.cookie.split('; ').find((c) => c.startsWith(`${XSRF}=`));
  return pair === undefined ? null : pair.slice(XSRF.length + 1) || null;
}

const writeXsrf = (value: string) => (document.cookie = `${XSRF}=${value}; path=/`);
const expireXsrf = () =>
  (document.cookie = `${XSRF}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`);

/**
 * The API's CSRF rules as the browser sees them over plain HTTP: every request without the cookie gets a new one, an
 * unsafe request whose header does not match is refused 403, a login answers with a new cookie, and a logout only
 * expires it.
 */
class FakeApi {
  signedIn = false;
  private issued = 0;
  readonly seen: string[] = [];

  answer(req: TestRequest): void {
    const { method, url } = req.request;
    this.seen.push(`${method} ${url}`);
    let token = readXsrf();
    if (token === null) {
      token = `tok-${++this.issued}`;
      writeXsrf(token);
    }
    if (method === 'POST' && req.request.headers.get('X-XSRF-TOKEN') !== token) {
      req.flush({ title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });
      return;
    }
    if (method === 'POST' && url === LOGIN_PATH) {
      this.signedIn = true;
      writeXsrf(`tok-${++this.issued}`);
      req.flush('', { status: 204, statusText: 'No Content' });
    } else if (method === 'POST' && url === LOGOUT_PATH) {
      this.signedIn = false;
      expireXsrf();
      req.flush('', { status: 204, statusText: 'No Content' });
    } else if (method === 'GET' && url === SESSION_PATH) {
      if (this.signedIn) {
        req.flush({ username: 'operator' });
      } else {
        req.flush(
          { title: 'Unauthorized', status: 401 },
          { status: 401, statusText: 'Unauthorized' },
        );
      }
    } else {
      throw new Error(`unexpected request ${method} ${url}`);
    }
  }
}

describe('SessionService sign in again after a sign out on the same page', () => {
  let http: HttpTestingController;
  let service: SessionService;
  let api: FakeApi;

  beforeEach(() => {
    expireXsrf();
    TestBed.configureTestingModule({
      providers: [provideApiHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(SessionService);
    api = new FakeApi();
  });

  afterEach(() => {
    http.verify();
    expireXsrf();
  });

  /** Answers every request the service sends, as the API would, until `work` settles. */
  async function served<T>(work: Promise<T>): Promise<T> {
    let settled = false;
    const result = work.finally(() => (settled = true));
    for (let i = 0; i < 50 && !settled; i++) {
      http.match(() => true).forEach((req) => api.answer(req));
      await new Promise((r) => setTimeout(r));
    }
    return result;
  }

  it('signs in on the first press after a sign out', async () => {
    await served(service.load());
    expect(await served(service.signIn('operator', 'pw'))).toEqual({ kind: 'signed_in' });
    await served(service.signOut());
    expect(service.state()).toEqual({ kind: 'anonymous' });

    const again = await served(service.signIn('operator', 'pw'));

    expect(again, `requests: ${api.seen.join(', ')}`).toEqual({ kind: 'signed_in' });
    expect(service.state()).toEqual({ kind: 'signed_in', username: 'operator' });
  });
});
