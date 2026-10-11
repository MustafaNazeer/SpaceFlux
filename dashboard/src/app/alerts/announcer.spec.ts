import { Alert, ALERT_STORED, AlertStoredData } from '../data/queries';
import { Segment } from '../format';
import { dataOf } from '../../testing/graphql';
import { ALERTS_ANONYMOUS } from '../../testing/fixtures';
import { AlertList, noticeAlert } from './alert-list';
import { Announcer, pollAdded } from './announcer';

const RECORDED = ALERTS_ANONYMOUS.alerts.items as unknown as Record<string, unknown>[];

const plain = (segments: Segment[]) =>
  segments.map((s) => (s.kind === 'text' ? s.text : s.kind === 'time' ? s.label : s.unit)).join('');

/** Alert `i` is a copy of recorded item `i % 6` under the id `a<i>`. */
function alert(i: number): Alert {
  const { acknowledgement: _, ...rest } = RECORDED[i % RECORDED.length];
  return noticeAlert(
    dataOf<AlertStoredData>(ALERT_STORED, { alert_stored: { ...rest, event_id: `a${i}` } })
      .alert_stored,
  );
}

const listOf = (alerts: Alert[]): AlertList => ({ items: alerts, more: false });
const ids = (alerts: Alert[]) => alerts.map((a) => a.event_id);

describe('Announcer', () => {
  it('covers 7 alerts queued before one flush with one grouped entry naming the newest', () => {
    const a = new Announcer(5);
    const seven = [0, 1, 2, 3, 4, 5, 6].map(alert);
    for (const x of seven) {
      a.queue([x]);
    }
    // The list shows the newest first, so the last one queued is at the top.
    a.flush(listOf([...seven].reverse()));
    expect(a.entries.length).toBe(1);
    expect(plain(a.entries[0].segments)).toMatch(/^7 new alerts\. Top of the list, /);
    expect(a.entries[0].eventIds).toEqual(ids([...seven].reverse()));
  });

  it('keeps a single alert in its own sentence', () => {
    const a = new Announcer(5);
    a.queue([alert(4)]);
    a.flush(listOf([alert(4), alert(0)]));
    expect(plain(a.entries[0].segments)).toMatch(/^New alert, G4 Severe: /);
  });

  it('announces each alert once, whichever way it arrives again', () => {
    const a = new Announcer(5);
    expect(a.queue([alert(1)])).toBe(true);
    expect(a.queue([alert(1)])).toBe(false);
    a.flush(listOf([alert(1)]));
    expect(a.queue([alert(1)])).toBe(false);
    expect(a.flush(listOf([alert(1)]))).toBe(false);
    expect(a.entries.length).toBe(1);
  });

  it('holds queued alerts while no list is shown, and speaks them once the list shows them', () => {
    const a = new Announcer(5);
    a.queue([alert(1)]);
    expect(a.flush(undefined)).toBe(false);
    expect(a.entries).toEqual([]);
    expect(a.waiting).toBe(true);
    expect(a.flush(listOf([alert(1), alert(2)]))).toBe(true);
    expect(a.entries[0].eventIds).toEqual(['a1']);
    expect(a.waiting).toBe(false);
  });

  it('never speaks an alert the shown list does not hold', () => {
    const a = new Announcer(5);
    a.queue([alert(1), alert(2)]);
    a.flush(listOf([alert(2)]));
    expect(a.entries[0].eventIds).toEqual(['a2']);
    expect(a.waiting).toBe(false);
  });

  it('trims old entries only once they have rendered, keeping the newest five', () => {
    const a = new Announcer(5);
    for (let i = 0; i < 7; i++) {
      a.queue([alert(i)]);
      a.flush(listOf([alert(i)]));
    }
    expect(a.entries.length).toBe(7);
    a.rendered(a.entries[6].id);
    a.queue([alert(7)]);
    a.flush(listOf([alert(7)]));
    expect(a.entries.map((e) => e.eventIds[0])).toEqual(['a3', 'a4', 'a5', 'a6', 'a7']);
  });

  it('keeps an entry that has not rendered even past five', () => {
    const a = new Announcer(5);
    for (let i = 0; i < 5; i++) {
      a.queue([alert(i)]);
      a.flush(listOf([alert(i)]));
    }
    a.rendered(a.entries[0].id);
    for (const i of [5, 6]) {
      a.queue([alert(i)]);
      a.flush(listOf([alert(i)]));
    }
    expect(a.entries.map((e) => e.eventIds[0])).toEqual(['a1', 'a2', 'a3', 'a4', 'a5', 'a6']);
  });

  it('sums up alerts that arrived while paused in one entry, and never speaks them again', () => {
    const a = new Announcer(5);
    expect(a.paused([alert(1), alert(2)], false)).toBe(true);
    expect(plain(a.entries[0].segments)).toBe('2 alerts arrived while paused.');
    expect(a.queue([alert(2)])).toBe(false);
    expect(a.paused([], false)).toBe(false);
    expect(a.paused([alert(1)], false)).toBe(false);
    expect(a.entries.length).toBe(1);
  });
});

describe('Announcer, spoken once across paths', () => {
  it('says or more in the summary when the refresh could not count every alert', () => {
    const a = new Announcer(5);
    a.paused([alert(1), alert(2)], true);
    expect(plain(a.entries[0].segments)).toBe('2 or more alerts arrived while paused.');
  });

  it('lets the paused summary take over a queued alert, so the flush says nothing more', () => {
    const a = new Announcer(5);
    a.queue([alert(1)]);
    a.paused([alert(1), alert(2)], false);
    expect(a.waiting).toBe(false);
    expect(a.flush(listOf([alert(1), alert(2)]))).toBe(false);
    expect(a.entries.map((e) => e.eventIds)).toEqual([['a1', 'a2']]);
  });

  it('never flushes an alert already spoken, even if it was queued before', () => {
    const a = new Announcer(5);
    a.queue([alert(1), alert(2)]);
    a.paused([alert(1)], false);
    a.flush(listOf([alert(1), alert(2)]));
    expect(a.entries.map((e) => e.eventIds)).toEqual([['a1'], ['a2']]);
  });
});

describe('pollAdded', () => {
  const page = (alerts: Alert[]) => ({ items: alerts, next: null as string | null });

  it('adds nothing on the first answer', () => {
    expect(pollAdded(undefined, page([alert(0), alert(1)]))).toEqual({ alerts: [], more: false });
  });

  it('takes the alerts above the newest one the previous answer listed', () => {
    expect(
      ids(
        pollAdded(page([alert(2), alert(3)]), page([alert(0), alert(1), alert(2), alert(3)]))
          .alerts,
      ),
    ).toEqual(['a0', 'a1']);
    expect(pollAdded(page([alert(2), alert(3)]), page([alert(2), alert(3)]))).toEqual({
      alerts: [],
      more: false,
    });
  });

  it('takes every alert the previous answer did not list when its newest has gone from the page', () => {
    const gone = pollAdded(page([alert(9), alert(3)]), page([alert(0), alert(3)]));
    expect(ids(gone.alerts)).toEqual(['a0']);
    expect(gone.more).toBe(false);
  });

  it('flags that more may exist when the newest one has gone from a page that has more after it', () => {
    const full = pollAdded(page([alert(9)]), { items: [alert(0), alert(1)], next: 'Mg' });
    expect(ids(full.alerts)).toEqual(['a0', 'a1']);
    expect(full.more).toBe(true);
    expect(pollAdded(page([alert(1)]), { items: [alert(0), alert(1)], next: 'Mg' }).more).toBe(
      false,
    );
  });

  it('takes every alert of an answer that follows an empty one', () => {
    expect(ids(pollAdded(page([]), page([alert(0)])).alerts)).toEqual(['a0']);
  });
});
