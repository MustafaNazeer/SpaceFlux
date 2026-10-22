import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  output,
  signal,
  untracked,
} from '@angular/core';

import { Segments } from '../badge/segments';
import { at, hms, Segment, text } from '../format';
import { Backoff, SessionService, SignInResult } from './session.service';

interface FormLine {
  lead: string;
  rest: Segment[];
  failure: boolean;
  /** The fields a "missing" line names; it empties once all of them hold a character. */
  missing?: { user: boolean; pass: boolean };
  backoff?: boolean;
}

const NO_LINE: FormLine = { lead: '', rest: [], failure: false };
const notSignedIn = (rest: string): FormLine => ({
  lead: 'Not signed in.',
  rest: [text(rest)],
  failure: true,
});

/** The form's line while the API is backing off, naming when Sign in works again. */
export function backoffLine(backoff: Backoff): FormLine {
  return {
    lead: 'Not signed in.',
    rest: [
      text('Too many sign in attempts were made. Try again at '),
      at(backoff.until, hms, ' UTC'),
      text(`, in ${backoff.seconds} ${backoff.seconds === 1 ? 'second' : 'seconds'}.`),
    ],
    failure: true,
    backoff: true,
  };
}

/** The form's line for a sign in that did not succeed. A backoff with a usable Retry-After is `backoffLine`. */
export function signInLine(result: SignInResult): FormLine {
  switch (result.kind) {
    case 'signed_in':
    case 'backoff':
      return NO_LINE;
    case 'wrong':
      return notSignedIn('The username or password is not correct. Check both and try again.');
    case 'backoff_later':
      return notSignedIn('Too many sign in attempts were made. Try again later.');
    case 'refused':
      return notSignedIn('The server refused the request. Try again.');
    case 'no_session':
      return notSignedIn(
        'The server could not be reached or had a problem, and no session was found. Try again.',
      );
    case 'not_confirmed':
      return {
        lead: 'Sign in not confirmed.',
        rest: [text('The server could not be reached or had a problem. Try again.')],
        failure: true,
      };
  }
}

/** What a press with an empty field says, naming each empty field. */
export function missingLine(user: boolean, pass: boolean): FormLine {
  const what =
    user && pass
      ? 'Enter both the username and the password.'
      : user
        ? 'Enter the username.'
        : 'Enter the password.';
  return { ...notSignedIn(what), missing: { user, pass } };
}

let nextId = 0;

/**
 * The operator sign in form, opened in the header in place of the Sign in button. Fields keep their values after a
 * failure; while a request is out they are read only, the buttons are marked aria-disabled, and Escape does nothing.
 * A backoff the API asked for is kept by the session service, so closing and reopening the form does not end it.
 */
@Component({
  selector: 'app-sign-in-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Segments],
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `<form class="signin-form" method="post" aria-label="Operator sign in" novalidate (submit)="submit($event)" (keydown)="key($event)">
    <div class="field"><label [for]="ids.user">Username</label><input [id]="ids.user" name="username" type="text" autocomplete="username" spellcheck="false" autocapitalize="none" [readOnly]="busy()" [attr.aria-invalid]="userInvalid() ? 'true' : null" [attr.aria-describedby]="describedBy().user" [value]="username()" (input)="typed('user', $event)" /></div>
    <div class="field"><label [for]="ids.pass">Password</label><input [id]="ids.pass" name="password" type="password" autocomplete="current-password" [readOnly]="busy()" [attr.aria-invalid]="passInvalid() ? 'true' : null" [attr.aria-describedby]="describedBy().pass" [value]="password()" (input)="typed('pass', $event)" /></div>
    <div class="ack-actions">
      <button class="btn btn-primary" type="submit" [attr.aria-disabled]="busy() || blocked() ? 'true' : null">Sign in</button>
      <button class="btn btn-secondary" type="button" [attr.aria-disabled]="busy() ? 'true' : null" (click)="cancel()">Cancel</button>
    </div>
    <p [id]="ids.line" class="status-line" role="status"><span [class]="line().failure ? 'inline-message' : 'visually-hidden'">@if (line().lead) {<strong>{{ line().lead }}</strong>{{ ' ' }}}<app-segments [segments]="line().rest" /></span></p>
    <p class="caption">Reading needs no sign in. Signing in only adds acknowledgement.</p>
  </form>`,
})
export class SignInForm {
  /** `cancel` returns focus to Sign in; `success` leaves focus to the header, which moves it to Sign out. */
  readonly closed = output<'cancel' | 'success'>();

  private readonly session = inject(SessionService);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  private destroyed = false;
  private relay: ReturnType<typeof setTimeout> | undefined;

  protected readonly ids = (() => {
    const n = nextId++;
    return { user: `signin-user-${n}`, pass: `signin-pass-${n}`, line: `signin-line-${n}` };
  })();
  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  /** True while the API is backing off: Sign in sends nothing until the time the line names. */
  protected readonly blocked = computed(() => this.session.backoff() !== null);
  protected readonly line = signal<FormLine>(NO_LINE);
  protected readonly userInvalid = computed(() => !!this.line().missing?.user && !this.username());
  protected readonly passInvalid = computed(() => !!this.line().missing?.pass && !this.password());
  protected readonly describedBy = computed(() => {
    const line = this.line();
    if (!line.failure) {
      return { user: null, pass: null };
    }
    if (line.missing) {
      return {
        user: this.userInvalid() ? this.ids.line : null,
        pass: this.passInvalid() ? this.ids.line : null,
      };
    }
    return { user: this.ids.line, pass: this.ids.line };
  });

  constructor() {
    afterNextRender(() => this.field('user').focus());
    effect(() => {
      const backoff = this.session.backoff();
      untracked(() => {
        if (backoff) {
          this.line.set(backoffLine(backoff));
        } else if (this.line().backoff) {
          this.line.set(NO_LINE);
        }
      });
    });
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      clearTimeout(this.relay);
    });
  }

  protected typed(which: 'user' | 'pass', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    (which === 'user' ? this.username : this.password).set(value);
    const missing = this.line().missing;
    if (missing && (!missing.user || this.username()) && (!missing.pass || this.password())) {
      this.line.set(NO_LINE);
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy() || this.blocked()) {
      return;
    }
    const user = !this.username();
    const pass = !this.password();
    if (user || pass) {
      // Emptied first, and set again in a later task once the empty line is drawn, so a repeated press is spoken.
      this.line.set(NO_LINE);
      afterNextRender(
        () => {
          clearTimeout(this.relay);
          this.relay = setTimeout(() => !this.destroyed && this.line.set(missingLine(user, pass)));
        },
        { injector: this.injector },
      );
      this.field(user ? 'user' : 'pass').focus();
      return;
    }
    this.busy.set(true);
    this.line.set({ lead: '', rest: [text('Signing in.')], failure: false });
    const result = await this.session.signIn(this.username(), this.password());
    if (this.destroyed) {
      return;
    }
    this.busy.set(false);
    if (result.kind === 'signed_in') {
      this.closed.emit('success');
      return;
    }
    const backoff = this.session.backoff();
    this.line.set(result.kind === 'backoff' && backoff ? backoffLine(backoff) : signInLine(result));
  }

  protected cancel(): void {
    if (!this.busy()) {
      this.closed.emit('cancel');
    }
  }

  protected key(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.cancel();
    }
  }

  private field(which: 'user' | 'pass'): HTMLInputElement {
    return this.host.querySelector<HTMLInputElement>(
      `#${which === 'user' ? this.ids.user : this.ids.pass}`,
    )!;
  }
}
