import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { Component, inject, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideApollo } from 'apollo-angular';

import { apolloOptions, GRAPHQL_PATH } from '../data/graphql';
import { Acknowledgement } from '../data/queries';
import { SESSION_PATH, SessionService } from '../session/session.service';
import { provideFakeSocket } from '../../testing/fake-socket';
import { ACK_PATH } from './ack-api';
import { answerAcks, EVENT_ID, signedIn } from '../../testing/acks';
import { AckActivity } from './ack-activity';
import { AckChange, AckControls } from './ack-controls';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();
const ACTED = '2026-10-22T10:15:00.123456Z';
const row = (action: string, note?: string) => ({
  event_id: EVENT_ID,
  action,
  principal: 'operator',
  acted_at: ACTED,
  ...(note === undefined ? {} : { note }),
});
const ACKED: Acknowledgement = {
  action: 'acknowledge',
  acted_at: ACTED,
  principal: 'operator',
  note: null,
};
const status = (s: number) => ({ status: s, statusText: 'x' });
const ASTRAL = String.fromCodePoint(0x1f6f0);

@Component({
  imports: [AckControls],
  providers: [AckActivity],
  template: `<ul>
    <li class="item">
      <h3>
        <a class="title-link" id="item-title" href="/alerts/x">{{ title }}</a>
      </h3>
      <app-ack-controls
        [eventId]="id"
        [ack]="ack()"
        [page]="page()"
        [title]="page() ? '' : title"
        [titleId]="page() ? null : 'item-title'"
        (changed)="onChange($event)"
      />
    </li>
  </ul>`,
})
class Host {
  readonly id = EVENT_ID;
  readonly title =
    'OBJECT AJ (57036) and SL-12 DEB (27958), miss distance 2 km at 2026-09-30 03:34:37 UTC';
  readonly ack = signal<Acknowledgement | null>(null);
  readonly page = signal(false);
  readonly changes: AckChange[] = [];
  readonly activity = inject(AckActivity);

  onChange(c: AckChange): void {
    this.changes.push(c);
    this.ack.set(c.ack);
  }
}

describe('AckControls', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<Host>;
  let el: HTMLElement;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=tok-3; path=/';
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
    return signedIn(http);
  });

  afterEach(() => {
    http.verify();
    fixture?.destroy();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  async function mount(ack: Acknowledgement | null = null, page = false): Promise<void> {
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.ack.set(ack);
    fixture.componentInstance.page.set(page);
    document.body.appendChild(fixture.nativeElement);
    await fixture.whenStable();
    el = fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i++) {
      await new Promise((r) => setTimeout(r));
      await fixture.whenStable();
    }
  }

  const main = () => el.querySelector<HTMLButtonElement>('.ack-main')!;
  const line = () => el.querySelector<HTMLElement>('[role="status"]')!;
  /** The child that holds the line's text and its shown or hidden styling; the live element keeps one style. */
  const shown = () => line().firstElementChild!.className;
  const retry = () => el.querySelector<HTMLButtonElement>('.ack-retry');
  const details = () => el.querySelector<HTMLDetailsElement>('details');
  const textarea = () => el.querySelector<HTMLTextAreaElement>('textarea')!;
  const counter = () => squash(el.querySelector('.note-counter')?.textContent);
  const posts = () => http.match((r) => r.method === 'POST' && r.url === ACK_PATH);
  const post = () => http.expectOne((r) => r.method === 'POST' && r.url === ACK_PATH);

  async function press(button: HTMLElement): Promise<void> {
    button.focus();
    button.click();
    await fixture.whenStable();
  }

  async function openNote(): Promise<void> {
    details()!.open = true;
    details()!.dispatchEvent(new Event('toggle'));
    await fixture.whenStable();
  }

  async function type(text: string): Promise<void> {
    textarea().value = text;
    textarea().dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function submitNote(): Promise<void> {
    const submit = el.querySelector<HTMLButtonElement>('form button[type="submit"]')!;
    submit.focus();
    submit.click();
    await fixture.whenStable();
  }

  it('starts with Acknowledge, the note disclosure and an empty, hidden status line in the page', async () => {
    await mount();
    expect(squash(main().textContent)).toBe('Acknowledge');
    expect(main().classList).toContain('btn-primary');
    expect(squash(el.querySelector('summary')?.textContent)).toBe('Acknowledge with a note');
    expect(shown()).toBe('visually-hidden');
    expect(line().textContent).toBe('');
    expect(line().getAttribute('role')).toBe('status');
    expect(retry()).toBeNull();
  });

  it('keeps focus on the button when a refresh changes its action', async () => {
    await mount();
    main().focus();
    fixture.componentInstance.ack.set(ACKED);
    await fixture.whenStable();
    expect(squash(main().textContent)).toBe('Unacknowledge');
    expect(document.activeElement).toBe(main());
  });

  it('offers Unacknowledge alone, with no note, once acknowledged', async () => {
    await mount(ACKED);
    expect(squash(main().textContent)).toBe('Unacknowledge');
    expect(main().classList).toContain('btn-secondary');
    expect(details()).toBeNull();
  });

  it('acknowledges with one press, holding the list and keeping focus while the request is out', async () => {
    await mount();
    await press(main());
    const req = post();
    expect(req.request.body).toEqual({ action: 'acknowledge' });
    expect(main().getAttribute('aria-disabled')).toBe('true');
    expect(main().hasAttribute('disabled')).toBe(false);
    expect(document.activeElement).toBe(main());
    expect(el.querySelector('form button[type="submit"]')?.getAttribute('aria-disabled')).toBe(
      'true',
    );
    expect(squash(line().textContent)).toBe('Acknowledging.');
    expect(shown()).toBe('visually-hidden');
    expect(fixture.componentInstance.activity.active()).toBe(true);
    await press(main());
    expect(posts().length).toBe(0);
    req.flush(row('acknowledge'), status(201));
    await settle();
    expect(squash(main().textContent)).toBe('Unacknowledge');
    expect(document.activeElement).toBe(main());
    expect(squash(line().textContent)).toBe('Acknowledged at 10:15 UTC.');
    expect(shown()).toBe('visually-hidden');
    expect(fixture.componentInstance.changes).toEqual([
      { ack: { ...ACKED, note: null }, reason: 'saved' },
    ]);
    expect(fixture.componentInstance.activity.active()).toBe(false);
  });

  it('unacknowledges with one press and no note, and puts the date on the time on the alert page', async () => {
    await mount(ACKED, true);
    await press(main());
    const req = post();
    expect(req.request.body).toEqual({ action: 'unacknowledge' });
    expect(squash(line().textContent)).toBe('Unacknowledging.');
    req.flush(row('unacknowledge'), status(201));
    await settle();
    expect(squash(main().textContent)).toBe('Acknowledge');
    expect(document.activeElement).toBe(main());
    expect(squash(line().textContent)).toBe('Unacknowledged at 2026-10-22 10:15 UTC.');
  });

  describe('the note', () => {
    it('counts code points, not UTF-16 units, and has no maxlength', async () => {
      await mount();
      await openNote();
      expect(textarea().hasAttribute('maxlength')).toBe(false);
      expect(textarea().getAttribute('placeholder')).toBe('What you checked');
      expect(counter()).toBe('0 of 500 characters');
      const ids = textarea().getAttribute('aria-describedby')!.split(' ');
      expect(ids.map((id) => squash(document.getElementById(id)?.textContent))).toEqual([
        'Up to 500 characters; some symbols count as more than one. Shown only to the signed in operator.',
        '0 of 500 characters',
      ]);
      // 500 astral characters are 1,000 UTF-16 units and still within the limit.
      await type(ASTRAL.repeat(500));
      expect(counter()).toBe('500 of 500 characters');
      expect(textarea().getAttribute('aria-invalid')).toBeNull();
      expect(line().textContent).toBe('');
    });

    it('says once, hidden, each time the note crosses over the limit, and empties silently when back within', async () => {
      await mount();
      await openNote();
      await type(`${ASTRAL.repeat(499)}ab`);
      expect(counter()).toBe('501 of 500 characters, 1 over the limit');
      expect(el.querySelector('.note-counter')?.classList).toContain('is-over');
      expect(textarea().getAttribute('aria-invalid')).toBe('true');
      expect(squash(line().textContent)).toBe('The note is over the 500 character limit.');
      expect(shown()).toBe('visually-hidden');
      await type(`${ASTRAL.repeat(499)}abc`);
      expect(counter()).toBe('502 of 500 characters, 2 over the limit');
      await type(ASTRAL.repeat(500));
      expect(line().textContent).toBe('');
      expect(textarea().getAttribute('aria-invalid')).toBeNull();
    });

    it('sends nothing while over and moves focus to the field', async () => {
      await mount();
      await openNote();
      await type('x'.repeat(501));
      await submitNote();
      expect(posts().length).toBe(0);
      expect(document.activeElement).toBe(textarea());
    });

    it('acknowledges with the note, then closes the form, clears it and moves focus to Unacknowledge', async () => {
      await mount();
      await openNote();
      expect(fixture.componentInstance.activity.active()).toBe(true);
      await type(`Checked ${ASTRAL}`);
      await submitNote();
      const req = post();
      expect(req.request.body).toEqual({ action: 'acknowledge', note: `Checked ${ASTRAL}` });
      expect(main().getAttribute('aria-disabled')).toBe('true');
      req.flush(row('acknowledge', `Checked ${ASTRAL}`), status(201));
      await settle();
      expect(squash(line().textContent)).toBe('Acknowledged at 10:15 UTC with a note.');
      expect(document.activeElement).toBe(main());
      expect(squash(main().textContent)).toBe('Unacknowledge');
      expect(fixture.componentInstance.activity.active()).toBe(false);
      // Unacknowledged again from elsewhere, the form comes back closed and empty.
      fixture.componentInstance.ack.set(null);
      await fixture.whenStable();
      expect(details()!.open).toBe(false);
      expect(textarea().value).toBe('');
    });

    it('sends no note when the field is empty', async () => {
      await mount();
      await openNote();
      await submitNote();
      expect(post().request.body).toEqual({ action: 'acknowledge' });
    });

    it('keeps the form open with the note after a failure', async () => {
      await mount();
      await openNote();
      await type('Keep me');
      await submitNote();
      post().flush({ status: 400 }, status(400));
      await settle();
      expect(details()!.open).toBe(true);
      expect(textarea().value).toBe('Keep me');
      expect(squash(line().textContent)).toBe('Not saved. The server refused the request.');
      // Try again after a failed note submit sends the same note, then closes and clears the form.
      await press(retry()!);
      const again = post();
      expect(again.request.body).toEqual({ action: 'acknowledge', note: 'Keep me' });
      again.flush(row('acknowledge', 'Keep me'), status(201));
      await settle();
      expect(details()).toBeNull();
      expect(fixture.componentInstance.activity.active()).toBe(false);
      fixture.componentInstance.ack.set(null);
      await fixture.whenStable();
      expect(details()!.open).toBe(false);
      expect(textarea().value).toBe('');
    });

    it('closes and clears the form when the main Acknowledge is pressed while it is open', async () => {
      await mount();
      await openNote();
      await type('draft');
      await press(main());
      post().flush(row('acknowledge'), status(201));
      await settle();
      expect(details()).toBeNull();
      expect(fixture.componentInstance.activity.active()).toBe(false);
      expect(document.activeElement).toBe(main());
      fixture.componentInstance.ack.set(null);
      await fixture.whenStable();
      expect(details()!.open).toBe(false);
      expect(textarea().value).toBe('');
    });

    it('closes and clears the form when the alert becomes acknowledged by any other path', async () => {
      await mount();
      await openNote();
      await type('draft');
      fixture.componentInstance.ack.set(ACKED);
      await fixture.whenStable();
      expect(fixture.componentInstance.activity.active()).toBe(false);
      fixture.componentInstance.ack.set(null);
      await fixture.whenStable();
      expect(details()!.open).toBe(false);
      expect(textarea().value).toBe('');
    });

    it('names the form for its item and points the buttons at the item title', async () => {
      await mount();
      expect(el.querySelector('form')?.getAttribute('aria-label')).toBe(
        `Acknowledge with a note, ${fixture.componentInstance.title}`,
      );
      expect(main().getAttribute('aria-describedby')).toBe('item-title');
      expect(el.querySelector('summary')?.getAttribute('aria-describedby')).toBe('item-title');
      expect(squash(main().textContent)).toBe('Acknowledge');
    });

    it('names the alert page form plainly', async () => {
      await mount(null, true);
      expect(el.querySelector('form')?.getAttribute('aria-label')).toBe('Acknowledge with a note');
      expect(main().getAttribute('aria-describedby')).toBeNull();
    });

    it('marks Cancel inactive while the note is being sent, so a failure still has the note', async () => {
      await mount();
      await openNote();
      await type('Keep me');
      await submitNote();
      const cancel = el.querySelector<HTMLButtonElement>('form button[type="button"]')!;
      expect(cancel.getAttribute('aria-disabled')).toBe('true');
      cancel.click();
      await fixture.whenStable();
      expect(textarea().value).toBe('Keep me');
      post().flush({ status: 400 }, status(400));
      await settle();
      expect(cancel.getAttribute('aria-disabled')).toBeNull();
      expect(textarea().value).toBe('Keep me');
    });

    it('renders a note as text, never as markup', async () => {
      await mount();
      await openNote();
      await type('<img src=x onerror=alert(1)>');
      expect(el.querySelector('img')).toBeNull();
      expect(textarea().value).toBe('<img src=x onerror=alert(1)>');
    });

    it('Cancel closes the form, clears the note and returns focus to the summary', async () => {
      await mount();
      await openNote();
      await type('draft');
      el.querySelector<HTMLButtonElement>('form button[type="button"]')!.click();
      await settle();
      expect(details()!.open).toBe(false);
      expect(textarea().value).toBe('');
      expect(document.activeElement).toBe(el.querySelector('summary'));
      expect(fixture.componentInstance.activity.active()).toBe(false);
    });

    it('closing with the summary keeps the note, and Escape does not close the form', async () => {
      await mount();
      await openNote();
      await type('draft');
      textarea().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await fixture.whenStable();
      expect(details()!.open).toBe(true);
      details()!.open = false;
      details()!.dispatchEvent(new Event('toggle'));
      await fixture.whenStable();
      expect(fixture.componentInstance.activity.active()).toBe(false);
      await openNote();
      expect(textarea().value).toBe('draft');
    });
  });

  describe('failures', () => {
    it.each([400, 415])(
      'shows %s as refused, with Try again repeating the same action, focus staying put',
      async (code) => {
        await mount();
        await press(main());
        post().flush({ status: code }, status(code));
        await settle();
        expect(shown()).toBe('inline-message');
        expect(line().className).toBe('status-line');
        expect(line().querySelector('strong')?.textContent).toBe('Not saved.');
        expect(squash(line().textContent)).toBe('Not saved. The server refused the request.');
        expect(document.activeElement).toBe(main());
        expect(squash(retry()?.textContent)).toBe('Try again');
        await press(retry()!);
        const again = post();
        expect(again.request.body).toEqual({ action: 'acknowledge' });
        expect(shown()).toBe('visually-hidden');
        expect(squash(line().textContent)).toBe('Acknowledging.');
        expect(retry()?.getAttribute('aria-disabled')).toBe('true');
        expect(document.activeElement).toBe(retry());
        again.flush(row('acknowledge'), status(201));
        await settle();
        expect(retry()).toBeNull();
        expect(document.activeElement).toBe(main());
        expect(squash(line().textContent)).toBe('Acknowledged at 10:15 UTC.');
      },
    );

    it('keeps the line at its height while Try again is out, so the button does not move', async () => {
      await mount();
      await press(main());
      post().flush({ status: 400 }, status(400));
      await settle();
      vi.spyOn(line(), 'getBoundingClientRect').mockReturnValue({ height: 44 } as DOMRect);
      await press(retry()!);
      expect(line().style.minHeight).toBe('44px');
      post().flush({ status: 400 }, status(400));
      await settle();
      expect(line().style.minHeight).toBe('');
      expect(squash(line().textContent)).toBe('Not saved. The server refused the request.');
    });

    it('shows 404 as no stored alert, with no Try again', async () => {
      await mount();
      await press(main());
      post().flush({ status: 404 }, status(404));
      await settle();
      expect(squash(line().textContent)).toBe('Not saved. No alert with this event id is stored.');
      expect(shown()).toBe('inline-message');
      expect(retry()).toBeNull();
    });

    it('after a 409 redraws from the fresh state and keeps focus on the action now shown when it was already done', async () => {
      await mount();
      await press(main());
      post().flush({ status: 409, detail: 'The alert cannot be acknowledged.' }, status(409));
      await settle();
      answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: ACKED });
      await settle();
      expect(fixture.componentInstance.changes).toEqual([{ ack: ACKED, reason: 'fetched' }]);
      expect(squash(main().textContent)).toBe('Unacknowledge');
      expect(document.activeElement).toBe(main());
      expect(squash(line().textContent)).toBe(
        'Not saved. This alert was already acknowledged, for example from an earlier press or another tab. The state above is current.',
      );
      expect(retry()).toBeNull();
    });

    it('after a 409 on unacknowledge says already unacknowledged', async () => {
      await mount(ACKED);
      await press(main());
      post().flush({ status: 409 }, status(409));
      await settle();
      answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: null });
      await settle();
      expect(squash(main().textContent)).toBe('Acknowledge');
      expect(document.activeElement).toBe(main());
      expect(squash(line().textContent)).toBe(
        'Not saved. This alert was already unacknowledged, for example from an earlier press or another tab. The state above is current.',
      );
    });

    it('after a 409 whose fresh state differs, says it cannot be acknowledged and keeps focus', async () => {
      await mount();
      await press(main());
      post().flush({ status: 409, detail: 'already acknowledged' }, status(409));
      await settle();
      answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: null });
      await settle();
      expect(squash(line().textContent)).toBe(
        'Not saved. The server says this alert cannot be acknowledged.',
      );
      expect(document.activeElement).toBe(main());
    });

    it.each([
      [false, 'Open the alert to see it.'],
      [true, 'Reload the page to see it.'],
    ])('after a 409 whose fetch fails (page %s)', async (page, tail) => {
      await mount(null, page);
      await press(main());
      post().flush({ status: 409 }, status(409));
      await settle();
      http.expectOne(GRAPHQL_PATH).error(new ProgressEvent('error'));
      await settle();
      expect(squash(line().textContent)).toBe(
        `Not saved. The server refused the change, and the alert's current state could not be loaded. ${tail}`,
      );
      expect(fixture.componentInstance.changes).toEqual([]);
    });

    it('ends the session on 401', async () => {
      await mount();
      const end = vi.spyOn(TestBed.inject(SessionService), 'end');
      await press(main());
      post().flush({ status: 401 }, status(401));
      await settle();
      expect(end).toHaveBeenCalledWith('ack');
      expect(TestBed.inject(SessionService).state()).toEqual({ kind: 'ended' });
    });

    it('ends the session on 401 even when the item is gone before the answer', async () => {
      await mount();
      await press(main());
      const req = post();
      fixture.destroy();
      req.flush({ status: 401 }, status(401));
      await new Promise((r) => setTimeout(r));
      expect(TestBed.inject(SessionService).state()).toEqual({ kind: 'ended' });
    });

    it('after a 403 checks the session once: none ends it, never resending the acknowledgement', async () => {
      await mount();
      const end = vi.spyOn(TestBed.inject(SessionService), 'end');
      await press(main());
      post().flush({ status: 403 }, status(403));
      await settle();
      http.expectOne(SESSION_PATH).flush({ status: 401 }, status(401));
      await settle();
      expect(end).toHaveBeenCalledWith('ack');
      expect(posts().length).toBe(0);
    });

    it('after a 403 with a live session, shows the refusal and lets Try again send once more', async () => {
      await mount();
      await press(main());
      post().flush({ status: 403 }, status(403));
      await settle();
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      await settle();
      expect(posts().length).toBe(0);
      expect(squash(line().textContent)).toBe('Not saved. The server refused the request.');
      await press(retry()!);
      post().flush(row('acknowledge'), status(201));
      await settle();
      expect(squash(line().textContent)).toBe('Acknowledged at 10:15 UTC.');
    });
  });

  describe('unknown outcomes', () => {
    it.each([500, 502, 503, 504])(
      'after %s fetches the alert again, never resends, and shows a written row as saved, clearing the note',
      async (code) => {
        await mount();
        await openNote();
        await type('Checked');
        await submitNote();
        post().flush({ status: code }, status(code));
        await settle();
        answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: ACKED });
        await settle();
        expect(posts().length).toBe(0);
        expect(squash(main().textContent)).toBe('Unacknowledge');
        expect(document.activeElement).toBe(main());
        expect(squash(line().textContent)).toBe('Acknowledged at 10:15 UTC.');
        expect(shown()).toBe('visually-hidden');
        expect(retry()).toBeNull();
        fixture.componentInstance.ack.set(null);
        await fixture.whenStable();
        expect(textarea().value).toBe('');
      },
    );

    it('says not confirmed, keeping the note, focus and Try again, when the fresh state does not show it', async () => {
      await mount();
      await openNote();
      await type('Checked');
      await submitNote();
      post().error(new ProgressEvent('error'));
      await settle();
      answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: null });
      await settle();
      expect(line().querySelector('strong')?.textContent).toBe('Not confirmed.');
      expect(squash(line().textContent)).toBe(
        'Not confirmed. The server could not be reached or had a problem, and the current state above does not show this alert as acknowledged. It was most likely not saved, but a delayed save can still go through later. Trying again never saves it twice.',
      );
      expect(details()!.open).toBe(true);
      expect(textarea().value).toBe('Checked');
      expect(retry()).not.toBeNull();
      await press(retry()!);
      expect(post().request.body).toEqual({ action: 'acknowledge', note: 'Checked' });
    });

    it.each([
      [false, 'Open the alert to see its state.'],
      [true, 'Reload the page to see its state.'],
    ])(
      'says the state could not be loaded when the fetch fails too (page %s)',
      async (page, tail) => {
        await mount(ACKED, page);
        await press(main());
        post().flush({ status: 503 }, status(503));
        await settle();
        http.expectOne(GRAPHQL_PATH).error(new ProgressEvent('error'));
        await settle();
        expect(squash(line().textContent)).toBe(
          `Not confirmed. The server could not be reached or had a problem, and the alert's current state could not be loaded, so it is not known whether it was unacknowledged. Trying again never saves it twice. ${tail}`,
        );
        expect(squash(main().textContent)).toBe('Unacknowledge');
        expect(retry()).not.toBeNull();
      },
    );
  });

  describe('a note when the session ends', () => {
    async function writing(text: string): Promise<void> {
      await mount();
      await openNote();
      await type(text);
    }

    it('keeps the note in place, read only, with focus in it, when the session ends on its own', async () => {
      await writing('Flare seen on the plot.');
      textarea().focus();
      TestBed.inject(SessionService).end('check');
      await settle();
      const kept = el.querySelector<HTMLElement>('.kept-note')!;
      expect(kept.getAttribute('role')).toBe('group');
      expect(kept.getAttribute('aria-label')).toBe(
        `Note not saved, ${fixture.componentInstance.title}`,
      );
      expect(squash(kept.querySelector('label')?.textContent)).toBe('Note, not saved');
      expect(textarea().readOnly).toBe(true);
      expect(textarea().value).toBe('Flare seen on the plot.');
      expect(document.activeElement).toBe(textarea());
      expect(squash(kept.querySelector('.kept-line')?.textContent)).toBe(
        'Note not saved. The session ended first. Sign in again to send it, or copy it.',
      );
      expect(el.querySelector('.ack-main')).toBeNull();
      expect(el.querySelector('form')).toBeNull();
      expect(fixture.componentInstance.activity.active()).toBe(true);
      expect(TestBed.inject(SessionService).line().text).toBe(
        'Your session has ended. Sign in again to acknowledge. The note you were writing is kept, not saved.',
      );
    });

    it('also keeps a note whose send was answered with 401, and sends nothing by itself', async () => {
      await writing('Checked');
      await submitNote();
      post().flush({ status: 401 }, status(401));
      await settle();
      expect(textarea().readOnly).toBe(true);
      expect(textarea().value).toBe('Checked');
      expect(posts().length).toBe(0);
    });

    it('makes the kept note its open, editable form again after the next sign in', async () => {
      await writing('Checked');
      TestBed.inject(SessionService).end('check');
      await settle();
      await signedIn(http);
      await settle();
      expect(el.querySelector('.kept-note')).toBeNull();
      expect(details()!.open).toBe(true);
      expect(textarea().readOnly).toBe(false);
      expect(textarea().value).toBe('Checked');
      expect(posts().length).toBe(0);
      await submitNote();
      post().flush(row('acknowledge', 'Checked'), status(201));
      await settle();
      expect(details()).toBeNull();
      expect(fixture.componentInstance.activity.active()).toBe(false);
    });

    it('keeps it read only after sign in when the alert is acknowledged by then', async () => {
      await writing('Checked');
      TestBed.inject(SessionService).end('check');
      await settle();
      fixture.componentInstance.ack.set(ACKED);
      await signedIn(http);
      await settle();
      expect(textarea().readOnly).toBe(true);
      expect(squash(el.querySelector('.kept-line')?.textContent)).toBe(
        'Note kept. This alert is now acknowledged, so this note cannot be sent. The state above shows any note the acknowledgement already has. Copy it if you need it.',
      );
      expect(squash(main().textContent)).toBe('Unacknowledge');
    });

    it('Discard note removes it and moves focus to the item title link', async () => {
      await writing('Checked');
      TestBed.inject(SessionService).end('check');
      await settle();
      const discard = Array.from(el.querySelectorAll('button')).find(
        (b) => squash(b.textContent) === 'Discard note',
      )!;
      discard.click();
      await settle();
      expect(el.querySelector('.kept-note')).toBeNull();
      expect(document.activeElement).toBe(el.querySelector('a.title-link'));
      expect(fixture.componentInstance.activity.active()).toBe(false);
    });

    it('discards the note on a sign out in this tab', async () => {
      await writing('Checked');
      const out = TestBed.inject(SessionService).signOut();
      http.expectOne('/api/auth/logout').flush('', { status: 204, statusText: 'No Content' });
      await out;
      await settle();
      expect(el.querySelector('.kept-note')).toBeNull();
      expect(el.querySelector('textarea')).toBeNull();
      expect(TestBed.inject(SessionService).line().text).toBe('Signed out.');
    });
  });

  describe('when the API does not answer', () => {
    beforeEach(() => vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] }));
    afterEach(() => vi.useRealTimers());

    async function timedOut(): Promise<TestRequest> {
      await mount();
      await press(main());
      const req = post();
      await vi.advanceTimersByTimeAsync(35_000);
      await settle();
      expect(req.cancelled).toBe(true);
      return req;
    }

    it('fetches the alert again after 35 s, and shows a written row as saved', async () => {
      await timedOut();
      answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: ACKED });
      await settle();
      expect(posts().length).toBe(0);
      expect(squash(main().textContent)).toBe('Unacknowledge');
      expect(document.activeElement).toBe(main());
      expect(squash(line().textContent)).toBe('Acknowledged at 10:15 UTC.');
      expect(retry()).toBeNull();
    });

    it('offers Try again when the fresh state was not reached or could not be read', async () => {
      await timedOut();
      answerAcks(http.expectOne(GRAPHQL_PATH), { [EVENT_ID]: null });
      await settle();
      expect(squash(line().textContent)).toBe(
        'Not confirmed. The server could not be reached or had a problem, and the current state above does not show this alert as acknowledged. It was most likely not saved, but a delayed save can still go through later. Trying again never saves it twice.',
      );
      expect(retry()).not.toBeNull();
      expect(posts().length).toBe(0);
    });

    it('offers Try again when the fetch fails', async () => {
      await timedOut();
      http.expectOne(GRAPHQL_PATH).error(new ProgressEvent('error'));
      await settle();
      expect(retry()).not.toBeNull();
      expect(posts().length).toBe(0);
    });
  });
});
