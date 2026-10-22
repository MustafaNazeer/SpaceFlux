import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { Component, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideApollo } from 'apollo-angular';

import { Clock } from '../data/clock';
import { apolloOptions } from '../data/graphql';
import { parseUtc } from '../format';
import { SessionService } from '../session/session.service';
import { signedIn } from '../../testing/acks';
import { provideFakeSocket } from '../../testing/fake-socket';
import { ACK_PATH } from './ack-api';
import { EVENT_ID } from '../../testing/acks';
import { AckHistory } from './ack-history';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

@Component({
  imports: [AckHistory],
  template: `<section class="panel"><app-ack-history [eventId]="id" /></section>`,
})
class Host {
  readonly id = EVENT_ID;
  readonly history = viewChild.required(AckHistory);
}

const entry = (minute: number, action = 'acknowledge', operator = true) => ({
  action,
  acted_at: `2026-09-29T06:${String(minute).padStart(2, '0')}:00.000000Z`,
  ...(operator ? { principal: 'operator', note: `Note ${minute}` } : {}),
});

describe('AckHistory', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<Host>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApollo(apolloOptions),
        provideFakeSocket(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(Clock).set(parseUtc('2026-09-29T18:00:00Z'));
    return signedIn(http);
  });

  afterEach(() => {
    http.verify();
    fixture?.destroy();
  });

  async function mount(): Promise<void> {
    fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    el = fixture.nativeElement as HTMLElement;
    await settle();
  }

  async function settle(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await new Promise((r) => setTimeout(r));
      await fixture.whenStable();
    }
  }

  const get = (): TestRequest => http.expectOne((r) => r.method === 'GET' && r.url === ACK_PATH);
  const status = () => el.querySelector<HTMLElement>('.ack-history > [role="status"]')!;
  /** The child that holds the line's text and its shown or hidden styling; the live element keeps one style. */
  const shown = () => status().firstElementChild!.className;
  const olderLine = () => squash(el.querySelector('.list-end [role="status"]')?.textContent);
  const endLine = () => squash(el.querySelector('.list-end .caption')?.textContent);
  const entries = () =>
    Array.from(el.querySelectorAll('.ack-history li')).map((li) => squash(li.textContent));
  const button = (label: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => squash(b.textContent) === label,
    );

  async function answer(items: object[], next: string | null = null, req = get()): Promise<void> {
    req.flush({ event_id: EVENT_ID, items, ...(next ? { next } : {}) });
    await settle();
  }

  it('loads the first 20 rows with the page on its own visible status line, under a History heading', async () => {
    await mount();
    const req = get();
    expect(req.request.urlWithParams).toBe(
      `${ACK_PATH}?event_id=${encodeURIComponent(EVENT_ID)}&limit=20`,
    );
    expect(squash(el.querySelector('h3')?.textContent)).toBe('History');
    expect(squash(status().textContent)).toBe('Loading the history.');
    expect(shown()).toBe('secondary');
    await answer([], null, req);
    expect(squash(status().textContent)).toBe('');
    expect(shown()).toBe('visually-hidden');
    expect(squash(el.querySelector('.ack-history > p.secondary')?.textContent)).toBe(
      'No acknowledgement is recorded for this alert yet.',
    );
  });

  it('lists each row newest first with its date and time, and the name and note for the operator', async () => {
    await mount();
    await answer([entry(2), entry(50, 'unacknowledge', false), entry(41, 'acknowledge', false)]);
    expect(entries()).toEqual([
      'Acknowledged 2026-09-29 06:02 UTC by operatorNote 2',
      'Unacknowledged 2026-09-29 06:50 UTC',
      'Acknowledged 2026-09-29 06:41 UTC',
    ]);
    expect(endLine()).toBe('All 3 entries are shown, newest first.');
  });

  it('says the one entry is shown', async () => {
    await mount();
    await answer([entry(2)]);
    expect(endLine()).toBe('The one entry is shown.');
  });

  it('loads older pages with Load older, then moves focus to the first entry added once the end is reached', async () => {
    await mount();
    await answer(
      Array.from({ length: 20 }, (_, i) => entry(i)),
      'c+1',
    );
    expect(endLine()).toBe('20 shown, newest first');
    const load = button('Load older')!;
    load.focus();
    load.click();
    await fixture.whenStable();
    const req = get();
    expect(req.request.urlWithParams).toContain('&after=c%2B1');
    expect(olderLine()).toBe('Loading older entries.');
    expect(load.getAttribute('aria-disabled')).toBe('true');
    load.click();
    http.expectNone((r) => r.url === ACK_PATH && r !== req.request);
    await answer([entry(30), entry(31), entry(32)], null, req);
    expect(entries().length).toBe(23);
    expect(olderLine()).toBe('3 older entries loaded. No older entries are stored.');
    expect(squash(el.querySelector('.list-end-line')?.textContent)).toBe(
      'All 23 entries are shown, newest first.',
    );
    expect(document.activeElement).toBe(el.querySelector('li[data-entry="20"]'));
  });

  it('says how many older entries were loaded while more remain, and keeps focus on Load older', async () => {
    await mount();
    await answer(
      Array.from({ length: 20 }, (_, i) => entry(i)),
      'c1',
    );
    const load = button('Load older')!;
    load.focus();
    load.click();
    await answer([entry(30)], 'c2');
    expect(olderLine()).toBe('1 older entry loaded.');
    expect(document.activeElement).toBe(button('Load older'));
  });

  it('moves focus to the end line when the last press added none, and reports a failed press', async () => {
    await mount();
    await answer(
      Array.from({ length: 20 }, (_, i) => entry(i)),
      'c1',
    );
    button('Load older')!.click();
    get().flush({ status: 500 }, { status: 500, statusText: 'x' });
    await settle();
    expect(olderLine()).toBe('Older entries could not be loaded. Use Load older to try again.');
    button('Load older')!.click();
    await answer([], null);
    expect(olderLine()).toBe('No older entries are stored.');
    expect(document.activeElement).toBe(el.querySelector('.list-end-line'));
  });

  it('shows a failed load with Try again, empties the line while it is out, and focuses the heading after', async () => {
    await mount();
    get().error(new ProgressEvent('error'));
    await settle();
    expect(squash(status().textContent)).toBe('The history could not be loaded.');
    expect(shown()).toBe('secondary');
    const again = button('Try again')!;
    again.focus();
    again.click();
    await fixture.whenStable();
    expect(squash(status().textContent)).toBe('');
    expect(again.getAttribute('aria-disabled')).toBe('true');
    expect(document.activeElement).toBe(again);
    await answer([entry(2)]);
    expect(button('Try again')).toBeUndefined();
    expect(document.activeElement).toBe(el.querySelector('h3'));
  });

  it('drops every name and note from every loaded page at once when the session ends', async () => {
    await mount();
    await answer(
      Array.from({ length: 20 }, (_, i) => entry(i)),
      'c1',
    );
    button('Load older')!.click();
    await answer([entry(30)]);
    TestBed.inject(SessionService).end('check');
    // Shown again at the next sign in, the rows must not still hold what an operator wrote.
    await signedIn(http);
    await settle();
    expect(entries().some((e) => e.includes('operator') || e.includes('Note'))).toBe(false);
    expect(entries()[20]).toBe('Acknowledged 2026-09-29 06:30 UTC');
  });

  it('reloads the first page, dropping older pages, and adds a row it is given at the top', async () => {
    await mount();
    await answer(
      Array.from({ length: 20 }, (_, i) => entry(i)),
      'c1',
    );
    button('Load older')!.click();
    await answer([entry(30)]);
    fixture.componentInstance.history().reload();
    await settle();
    expect(entries().length).toBe(21);
    const req = get();
    expect(req.request.urlWithParams).not.toContain('after');
    await answer([entry(5)], null, req);
    expect(entries()).toEqual(['Acknowledged 2026-09-29 06:05 UTC by operatorNote 5']);
    fixture.componentInstance.history().prepend({
      action: 'unacknowledge',
      acted_at: '2026-09-29T07:00:00.000000Z',
      principal: 'operator',
      note: null,
    });
    await settle();
    expect(entries()[0]).toBe('Unacknowledged 2026-09-29 07:00 UTC by operator');
    expect(endLine()).toBe('All 2 entries are shown, newest first.');
  });

  it('drops names and notes from rows that arrive after the session ended, so a later sign in shows none of them', async () => {
    await mount();
    const req = get();
    TestBed.inject(SessionService).end('check');
    await answer([entry(2)], null, req);
    await signedIn(http);
    await settle();
    expect(entries()).toEqual(['Acknowledged 2026-09-29 06:02 UTC']);
  });

  it('keeps the line at its height while Try again is out, so the button does not move', async () => {
    await mount();
    get().error(new ProgressEvent('error'));
    await settle();
    expect(status().className).toBe('status-line');
    vi.spyOn(status(), 'getBoundingClientRect').mockReturnValue({ height: 34 } as DOMRect);
    button('Try again')!.click();
    await fixture.whenStable();
    expect(status().style.minHeight).toBe('34px');
    get().error(new ProgressEvent('error'));
    await settle();
    expect(status().style.minHeight).toBe('');
    expect(squash(status().textContent)).toBe('The history could not be loaded.');
  });
});
