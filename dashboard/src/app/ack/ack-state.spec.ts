import { TestBed } from '@angular/core/testing';

import { Clock } from '../data/clock';
import { Acknowledgement } from '../data/queries';
import { parseUtc } from '../format';
import { AckState } from './ack-state';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

async function render(
  ack: Acknowledgement | null,
  showNote = false,
  now = '2026-09-29T20:00:00Z',
): Promise<HTMLElement> {
  TestBed.inject(Clock).set(parseUtc(now));
  const fixture = TestBed.createComponent(AckState);
  fixture.componentRef.setInput('ack', ack);
  fixture.componentRef.setInput('showNote', showNote);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('AckState', () => {
  it('shows an alert with no acknowledgement row as not acknowledged', async () => {
    const el = await render(null);
    expect(squash(el.textContent)).toBe('Not acknowledged');
    expect(el.querySelector('use')?.getAttribute('href')).toBe('#i-open');
    expect(el.querySelector('time')).toBeNull();
  });

  it('shows an unacknowledgement with its time and the open icon', async () => {
    const el = await render({
      action: 'unacknowledge',
      acted_at: '2026-09-29T16:59:00.000000Z',
      principal: null,
      note: null,
    });
    expect(squash(el.textContent)).toBe('Unacknowledged 16:59 UTC');
    expect(el.querySelector('time')?.getAttribute('datetime')).toBe('2026-09-29T16:59:00.000Z');
    expect(el.querySelector('use')?.getAttribute('href')).toBe('#i-open');
  });

  it('shows the note only where asked', async () => {
    const ack = {
      action: 'acknowledge',
      acted_at: '2026-09-29T06:02:00.000000Z',
      principal: 'operator',
      note: 'Checked.',
    };
    expect((await render(ack)).querySelector('.ack-note')).toBeNull();
    expect(squash((await render(ack, true)).querySelector('.ack-note')?.textContent)).toBe(
      'Checked.',
    );
  });

  it('shows the date of an action taken on another UTC day', async () => {
    const ack = {
      action: 'acknowledge',
      acted_at: '2026-09-29T23:58:00.000000Z',
      principal: null,
      note: null,
    };
    expect(squash((await render(ack, false, '2026-09-30T00:05:00Z')).textContent)).toBe(
      'Acknowledged 2026-09-29 23:58 UTC',
    );
    expect(squash((await render(ack, false, '2026-09-29T23:59:00Z')).textContent)).toBe(
      'Acknowledged 23:58 UTC',
    );
  });
});
