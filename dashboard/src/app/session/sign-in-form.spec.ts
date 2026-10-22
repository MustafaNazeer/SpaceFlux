import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { LOGIN_PATH, SESSION_PATH, SessionService } from './session.service';
import { SignInForm, signInLine } from './sign-in-form';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();
const status = (s: number, headers: Record<string, string> = {}) => ({
  status: s,
  statusText: 'x',
  headers,
});

describe('SignInForm', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<SignInForm>;
  let el: HTMLElement;
  let closed: string[];

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    const loaded = TestBed.inject(SessionService).load();
    http.expectOne(SESSION_PATH).flush({}, status(401));
    await loaded;
    fixture = TestBed.createComponent(SignInForm);
    closed = [];
    fixture.componentInstance.closed.subscribe((r) => closed.push(r));
    document.body.appendChild(fixture.nativeElement);
    el = fixture.nativeElement as HTMLElement;
    await settle();
  });

  afterEach(() => {
    http.verify();
    fixture.destroy();
  });

  async function settle(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await new Promise((r) => setTimeout(r));
      await fixture.whenStable();
    }
  }

  const user = () => el.querySelector<HTMLInputElement>('input[autocomplete="username"]')!;
  const pass = () => el.querySelector<HTMLInputElement>('input[autocomplete="current-password"]')!;
  const signIn = () => el.querySelector<HTMLButtonElement>('button[type="submit"]')!;
  const cancel = () => el.querySelector<HTMLButtonElement>('button[type="button"]')!;
  const line = () => el.querySelector<HTMLElement>('[role="status"]')!;
  /** The child that holds the line's text and its shown or hidden styling; the live element keeps one style. */
  const shown = () => line().firstElementChild!.className;

  async function fill(u: string, p: string): Promise<void> {
    user().value = u;
    user().dispatchEvent(new Event('input'));
    pass().value = p;
    pass().dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function press(): Promise<void> {
    signIn().focus();
    signIn().click();
    await fixture.whenStable();
  }

  async function failWith(code: number, headers: Record<string, string> = {}): Promise<void> {
    await fill('operator', 'pw');
    await press();
    http.expectOne(LOGIN_PATH).flush({ status: code }, status(code, headers));
    await settle();
  }

  it('is the named form with visible labels, autocomplete, the caption, and focus on Username', () => {
    expect(el.querySelector('form')?.getAttribute('aria-label')).toBe('Operator sign in');
    expect(Array.from(el.querySelectorAll('label')).map((l) => squash(l.textContent))).toEqual([
      'Username',
      'Password',
    ]);
    expect(el.querySelector(`label[for="${user().id}"]`)).not.toBeNull();
    expect(pass().type).toBe('password');
    expect(squash(el.querySelector('.caption')?.textContent)).toBe(
      'Reading needs no sign in. Signing in only adds acknowledgement.',
    );
    expect(document.activeElement).toBe(user());
    expect(user().getAttribute('aria-describedby')).toBeNull();
    expect(line().textContent).toBe('');
  });

  it('is a post form whose live line keeps one style in every state', async () => {
    expect(el.querySelector('form')?.getAttribute('method')).toBe('post');
    expect(line().className).toBe('status-line');
    await failWith(401);
    expect(line().className).toBe('status-line');
  });

  it('names both empty fields, marks each invalid, points them at the line and focuses the first', async () => {
    await press();
    await settle();
    expect(squash(line().textContent)).toBe(
      'Not signed in. Enter both the username and the password.',
    );
    expect(shown()).toBe('inline-message');
    expect(document.activeElement).toBe(user());
    for (const f of [user(), pass()]) {
      expect(f.getAttribute('aria-invalid')).toBe('true');
      expect(f.getAttribute('aria-describedby')).toBe(line().id);
    }
    http.expectNone(LOGIN_PATH);
  });

  it('names only the password when the username is filled, and focuses the password', async () => {
    await fill('operator', '');
    await press();
    await settle();
    expect(squash(line().textContent)).toBe('Not signed in. Enter the password.');
    expect(user().getAttribute('aria-invalid')).toBeNull();
    expect(user().getAttribute('aria-describedby')).toBeNull();
    expect(pass().getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(pass());
  });

  it('names only the username, counting a field of spaces as filled', async () => {
    await fill('', '   ');
    await press();
    await settle();
    expect(squash(line().textContent)).toBe('Not signed in. Enter the username.');
    expect(pass().getAttribute('aria-invalid')).toBeNull();
  });

  it('empties the line first and sets it in a later frame, so a second empty press is spoken again', async () => {
    await press();
    await settle();
    const texts: string[] = [];
    const watcher = new MutationObserver(() => texts.push(squash(line().textContent)));
    watcher.observe(line(), { subtree: true, childList: true, characterData: true });
    await press();
    await settle();
    watcher.disconnect();
    expect(texts[0]).toBe('');
    expect(texts.at(-1)).toBe('Not signed in. Enter both the username and the password.');
  });

  it('drops aria-invalid as a field gets a character, and empties the line once every named field is filled', async () => {
    await press();
    await settle();
    await fill('o', '');
    expect(user().getAttribute('aria-invalid')).toBeNull();
    expect(pass().getAttribute('aria-invalid')).toBe('true');
    expect(squash(line().textContent)).toBe(
      'Not signed in. Enter both the username and the password.',
    );
    await fill('o', 'p');
    expect(line().textContent).toBe('');
    expect(pass().getAttribute('aria-invalid')).toBeNull();
  });

  it('while the request is out: fields read only, buttons inactive, Escape and Cancel do nothing, the line hidden', async () => {
    await fill('operator', 'pw');
    await press();
    const req = http.expectOne(LOGIN_PATH);
    expect(user().readOnly).toBe(true);
    expect(pass().readOnly).toBe(true);
    expect(signIn().getAttribute('aria-disabled')).toBe('true');
    expect(cancel().getAttribute('aria-disabled')).toBe('true');
    expect(document.activeElement).toBe(signIn());
    expect(squash(line().textContent)).toBe('Signing in.');
    expect(shown()).toBe('visually-hidden');
    pass().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    cancel().click();
    await press();
    expect(closed).toEqual([]);
    http.expectNone(LOGIN_PATH);
    req.flush({ status: 401 }, status(401));
    await settle();
  });

  it('keeps both values and focus after wrong credentials, and points both fields at the message', async () => {
    await failWith(401);
    expect(squash(line().textContent)).toBe(
      'Not signed in. The username or password is not correct. Check both and try again.',
    );
    expect(line().querySelector('strong')?.textContent).toBe('Not signed in.');
    expect(user().value).toBe('operator');
    expect(pass().value).toBe('pw');
    expect(user().readOnly).toBe(false);
    expect(document.activeElement).toBe(signIn());
    expect(user().getAttribute('aria-describedby')).toBe(line().id);
    expect(pass().getAttribute('aria-describedby')).toBe(line().id);
  });

  it('reads 400 as a refused request', async () => {
    await failWith(400);
    expect(squash(line().textContent)).toBe(
      'Not signed in. The server refused the request. Try again.',
    );
  });

  it('reads a server error as an unknown outcome: one session check, and no session found', async () => {
    await failWith(503);
    http.expectOne(SESSION_PATH).flush({}, status(401));
    await settle();
    http.expectNone(LOGIN_PATH);
    expect(squash(line().textContent)).toBe(
      'Not signed in. The server could not be reached or had a problem, and no session was found. Try again.',
    );
  });

  it('says sign in is not confirmed when the session check fails too, and stays open while the state is unknown', async () => {
    await fill('operator', 'pw');
    await press();
    http.expectOne(LOGIN_PATH).error(new ProgressEvent('error'));
    await settle();
    http.expectOne(SESSION_PATH).error(new ProgressEvent('error'));
    await settle();
    expect(line().querySelector('strong')?.textContent).toBe('Sign in not confirmed.');
    expect(squash(line().textContent)).toBe(
      'Sign in not confirmed. The server could not be reached or had a problem. Try again.',
    );
    expect(TestBed.inject(SessionService).state()).toEqual({ kind: 'unknown' });
    TestBed.inject(SessionService)['retry'] &&
      clearTimeout(TestBed.inject(SessionService)['retry']);
  });

  it('reads a 403 as a refused request after one session check, and a press sends again', async () => {
    await fill('operator', 'pw');
    await press();
    http.expectOne(LOGIN_PATH).flush({ status: 403 }, status(403));
    await settle();
    http.expectOne(SESSION_PATH).flush({}, status(401));
    await settle();
    http.expectNone(LOGIN_PATH);
    expect(squash(line().textContent)).toBe(
      'Not signed in. The server refused the request. Try again.',
    );
    await press();
    http.expectOne(LOGIN_PATH).flush('', status(204));
    await settle();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    await settle();
    expect(closed).toEqual(['success']);
  });

  it('says to try again later when the backoff has no usable Retry-After, and Sign in stays usable', async () => {
    await failWith(429);
    expect(squash(line().textContent)).toBe(
      'Not signed in. Too many sign in attempts were made. Try again later.',
    );
    expect(signIn().getAttribute('aria-disabled')).toBeNull();
  });

  describe('while the API is backing off', () => {
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
      vi.setSystemTime(new Date('2026-09-29T18:00:10Z'));
    });
    afterEach(() => vi.useRealTimers());

    async function backOff(seconds: string): Promise<void> {
      await fill('operator', 'pw');
      await press();
      http.expectOne(LOGIN_PATH).flush({ status: 429 }, status(429, { 'Retry-After': seconds }));
      for (let i = 0; i < 5; i++) {
        await vi.advanceTimersByTimeAsync(0);
        await fixture.whenStable();
      }
    }

    it('names the time the wait ends, keeps Sign in inactive until then, and empties the line silently at that time', async () => {
      await backOff('120');
      expect(squash(line().textContent)).toBe(
        'Not signed in. Too many sign in attempts were made. Try again at 18:02:10 UTC, in 120 seconds.',
      );
      expect(line().querySelector('time')?.getAttribute('datetime')).toBe(
        '2026-09-29T18:02:10.000Z',
      );
      expect(signIn().getAttribute('aria-disabled')).toBe('true');
      await press();
      http.expectNone(LOGIN_PATH);
      await vi.advanceTimersByTimeAsync(119_999);
      await fixture.whenStable();
      expect(signIn().getAttribute('aria-disabled')).toBe('true');
      await vi.advanceTimersByTimeAsync(1);
      await fixture.whenStable();
      expect(signIn().getAttribute('aria-disabled')).toBeNull();
      expect(line().textContent).toBe('');
      await press();
      http.expectOne(LOGIN_PATH).flush({ status: 401 }, status(401));
      await vi.advanceTimersByTimeAsync(0);
    });

    it('keeps the backoff when the form is closed and opened again', async () => {
      await backOff('120');
      fixture.destroy();
      fixture = TestBed.createComponent(SignInForm);
      document.body.appendChild(fixture.nativeElement);
      el = fixture.nativeElement as HTMLElement;
      for (let i = 0; i < 3; i++) {
        await vi.advanceTimersByTimeAsync(0);
        await fixture.whenStable();
      }
      expect(signIn().getAttribute('aria-disabled')).toBe('true');
      expect(squash(line().textContent)).toBe(
        'Not signed in. Too many sign in attempts were made. Try again at 18:02:10 UTC, in 120 seconds.',
      );
      await vi.advanceTimersByTimeAsync(120_000);
      await fixture.whenStable();
      expect(signIn().getAttribute('aria-disabled')).toBeNull();
      expect(line().textContent).toBe('');
    });

    it('says one second in the singular', async () => {
      await backOff('1');
      expect(squash(line().textContent)).toBe(
        'Not signed in. Too many sign in attempts were made. Try again at 18:00:11 UTC, in 1 second.',
      );
    });
  });

  it('closes on Cancel and on Escape', async () => {
    cancel().click();
    pass().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(closed).toEqual(['cancel', 'cancel']);
  });

  it('closes as a success once signed in', async () => {
    await fill('operator', 'pw');
    await press();
    http.expectOne(LOGIN_PATH).flush('', status(204));
    await settle();
    http.expectOne(SESSION_PATH).flush({ username: 'operator' });
    await settle();
    expect(closed).toEqual(['success']);
  });

  it('builds no line for a success', () => {
    expect(signInLine({ kind: 'signed_in' })).toEqual({ lead: '', rest: [], failure: false });
  });
});
