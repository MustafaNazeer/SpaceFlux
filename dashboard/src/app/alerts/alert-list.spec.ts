import {
  ALERT_STORED,
  Alert,
  AlertNotice,
  AlertStoredData,
  RECENT_ALERTS,
  RecentAlertsData,
} from '../data/queries';
import { dataOf } from '../../testing/graphql';
import { ALERTS_ANONYMOUS } from '../../testing/fixtures';
import {
  EMPTY_LIVE,
  LiveList,
  noticeAlert,
  shown,
  withNotice,
  withOlderPage,
  withOlderStarted,
  withoutOlder,
  withPoll,
} from './alert-list';

const PAGE = 20;
const RECORDED = ALERTS_ANONYMOUS.alerts.items as unknown as Record<string, unknown>[];

function page(items: Record<string, unknown>[], next: string | null = null) {
  return dataOf<RecentAlertsData>(RECENT_ALERTS, { alerts: { items, next } }, { limit: PAGE })
    .alerts;
}

/** A notice for a recorded alert, under a new event_id unless one is given. */
function notice(from: number, eventId?: string): AlertNotice {
  const { acknowledgement: _, ...alert } = RECORDED[from];
  return dataOf<AlertStoredData>(ALERT_STORED, {
    alert_stored: { ...alert, event_id: eventId ?? alert['event_id'] },
  }).alert_stored;
}

const ids = (items: Alert[] | undefined) => (items ?? []).map((a) => a.event_id);

function add(list: LiveList, n: AlertNotice) {
  return withNotice(list, n, PAGE);
}

describe('alert list', () => {
  it('shows nothing until the first poll answers', () => {
    expect(shown(EMPTY_LIVE, PAGE)).toBeUndefined();
    expect(shown(add(EMPTY_LIVE, notice(5, 'new/1')).list, PAGE)).toBeUndefined();
  });

  it('shows a poll result as it came, newest first', () => {
    const list = withPoll(EMPTY_LIVE, page(RECORDED, 'NDE'));
    expect(ids(shown(list, PAGE)?.items)).toEqual(RECORDED.map((a) => a['event_id']));
    expect(shown(list, PAGE)?.more).toBe(true);
    expect(shown(withPoll(EMPTY_LIVE, page(RECORDED)), PAGE)?.more).toBe(false);
  });

  it('prepends a new notice, newest first, as an alert with no acknowledgement and no screening run', () => {
    let list = withPoll(EMPTY_LIVE, page(RECORDED.slice(2)));
    const first = add(list, notice(1, 'new/1'));
    expect(first.added?.event_id).toBe('new/1');
    list = first.list;
    const second = add(list, notice(0, 'new/2'));
    list = second.list;
    const items = shown(list, PAGE)!.items;
    expect(ids(items).slice(0, 3)).toEqual(['new/2', 'new/1', RECORDED[2]['event_id']]);
    expect(items[0].acknowledgement).toBeNull();
    expect(items[0].screening_run).toBeNull();
    expect(items[0].space_weather_level).toEqual(page([RECORDED[0]]).items[0].space_weather_level);
  });

  it('ignores a notice for an alert the poll already shows, and does not announce it', () => {
    const list = withPoll(EMPTY_LIVE, page(RECORDED));
    const again = add(list, notice(3));
    expect(again.added).toBeNull();
    expect(again.list).toBe(list);
  });

  it('ignores a second notice for an alert a notice already added', () => {
    const once = add(withPoll(EMPTY_LIVE, page(RECORDED)), notice(5, 'new/1'));
    const twice = add(once.list, notice(5, 'new/1'));
    expect(twice.added).toBeNull();
    expect(ids(shown(twice.list, PAGE)?.items).filter((id) => id === 'new/1').length).toBe(1);
  });

  it('keeps to the page size, and says more exist once notices push the oldest off', () => {
    const full = RECORDED.flatMap((a, i) =>
      [0, 1, 2, 3].map((k) => ({ ...a, event_id: `${a['event_id']}#${i}-${k}` })),
    ).slice(0, PAGE);
    let list = withPoll(EMPTY_LIVE, page(full));
    expect(shown(list, PAGE)?.more).toBe(false);
    list = add(list, notice(5, 'new/1')).list;
    const view = shown(list, PAGE)!;
    expect(view.items.length).toBe(PAGE);
    expect(view.items[0].event_id).toBe('new/1');
    expect(view.more).toBe(true);
  });

  it('lets a poll that lists a notice take over, in the poll order', () => {
    let list = withPoll(EMPTY_LIVE, page(RECORDED.slice(2)));
    list = add(list, notice(1)).list;
    list = add(list, notice(0)).list;
    // The poll is the source of truth for order, even where it differs from the order notices arrived in.
    const polled = [RECORDED[1], RECORDED[0], ...RECORDED.slice(2)];
    list = withPoll(list, page(polled));
    expect(ids(shown(list, PAGE)?.items)).toEqual(polled.map((a) => a['event_id']));
  });

  it('keeps a notice through one poll that does not list it yet, and drops it at the second', () => {
    let list = withPoll(EMPTY_LIVE, page(RECORDED.slice(1)));
    list = add(list, notice(0)).list;
    list = withPoll(list, page(RECORDED.slice(1)));
    expect(ids(shown(list, PAGE)?.items)[0]).toBe(RECORDED[0]['event_id']);
    list = withPoll(list, page(RECORDED.slice(1)));
    expect(ids(shown(list, PAGE)?.items)).toEqual(RECORDED.slice(1).map((a) => a['event_id']));
  });

  it('keeps one entry per event_id even if a poll repeats one', () => {
    const list = withPoll(EMPTY_LIVE, page([RECORDED[0], RECORDED[1], RECORDED[0]]));
    expect(ids(shown(list, PAGE)?.items)).toEqual([
      RECORDED[0]['event_id'],
      RECORDED[1]['event_id'],
    ]);
  });

  it('builds a list item from a notice with the notice fields unchanged', () => {
    const n = notice(5);
    expect(noticeAlert(n)).toEqual({ ...n, screening_run: null, acknowledgement: null });
  });

  describe('older pages', () => {
    /** n recorded alerts under the ids a/0, a/1, ..., newest first. */
    const run = (prefix: string, n: number) =>
      Array.from({ length: n }, (_, i) => ({
        ...RECORDED[i % RECORDED.length],
        event_id: `${prefix}/${i}`,
      }));

    it('lifts the cut when Load older starts, and pages after the poll cursor, the oldest alert then shown', () => {
      const first = withPoll(EMPTY_LIVE, page(run('a', PAGE), 'MjA'));
      const pushed = add(add(first, notice(5, 'new/1')).list, notice(4, 'new/2')).list;
      expect(shown(pushed, PAGE)!.items.length).toBe(PAGE);
      expect(ids(shown(pushed, PAGE)!.items).at(-1)).toBe('a/17');

      const started = withOlderStarted(pushed);
      expect(started.older).toEqual({ items: [], next: 'MjA' });
      const all = shown(started, PAGE)!;
      expect(all.items.length).toBe(PAGE + 2);
      expect(ids(all.items).at(-1)).toBe('a/19');
      expect(all.more).toBe(true);

      const paged = withOlderPage(started, page(run('b', 3), null));
      const after = shown(paged, PAGE)!;
      expect(ids(after.items).slice(-4)).toEqual(['a/19', 'b/0', 'b/1', 'b/2']);
      expect(after.more).toBe(false);
    });

    it('never cuts the list once an older page is loaded: what a refresh pushes off the page moves below it', () => {
      const first = withPoll(EMPTY_LIVE, page(run('a', 4), 'NA'));
      const paged = withOlderPage(withOlderStarted(first), page(run('b', 2), 'Ng'));
      const before = ids(shown(paged, 4)!.items);
      expect(before).toEqual(['a/0', 'a/1', 'a/2', 'a/3', 'b/0', 'b/1']);

      const fresh = [...run('n', 2), ...run('a', 2)];
      const refreshed = withPoll(paged, page(fresh, 'Mg'));
      const after = shown(refreshed, 4)!;
      expect(ids(after.items)).toEqual(['n/0', 'n/1', ...before]);
      expect(after.more).toBe(true);
      expect(refreshed.older?.next).toBe('Ng');
    });

    it('goes back to the first page, cut to its size again', () => {
      const first = withPoll(EMPTY_LIVE, page(run('a', 4), 'NA'));
      const paged = withOlderPage(withOlderStarted(first), page(run('b', 2), null));
      const back = withoutOlder(paged);
      expect(back.older).toBeUndefined();
      expect(ids(shown(back, 4)!.items)).toEqual(['a/0', 'a/1', 'a/2', 'a/3']);
      expect(withoutOlder(first)).toBe(first);
    });

    it('leaves the list alone when Load older starts before the first poll, or again while older pages show', () => {
      expect(withOlderStarted(EMPTY_LIVE)).toBe(EMPTY_LIVE);
      const started = withOlderStarted(withPoll(EMPTY_LIVE, page(run('a', 2), 'Mg')));
      expect(withOlderStarted(started)).toBe(started);
      expect(withOlderPage(EMPTY_LIVE, page(run('b', 1)))).toBe(EMPTY_LIVE);
    });
  });
});
