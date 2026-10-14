import { Alert } from '../data/queries';
import { Segment } from '../format';
import { ALERTS_ANONYMOUS } from '../../testing/fixtures';
import { alertView } from './alert-view';

const plain = (segments: Segment[]) =>
  segments.map((s) => (s.kind === 'text' ? s.text : s.kind === 'time' ? s.label : s.unit)).join('');

const approach = ALERTS_ANONYMOUS.alerts.items[5] as unknown as Alert;

function named(watchlist: string | null, other: string | null): Alert {
  const c = approach.close_approach!;
  return {
    ...approach,
    close_approach: {
      ...c,
      watchlist_object: { ...c.watchlist_object, name: watchlist },
      other_object: { ...c.other_object, name: other },
    },
  };
}

describe('alertView close approach title', () => {
  it('gives each named object its number in brackets', () => {
    expect(plain(alertView(approach, 0).title)).toBe(
      'OBJECT AJ (57036) and SL-12 DEB (27958), miss distance 2 km at 2026-09-30 03:34:37 UTC',
    );
  });

  it('writes an unnamed object as object and its number, capitalised only at the start', () => {
    expect(plain(alertView(named('OBJECT AJ', null), 0).title)).toBe(
      'OBJECT AJ (57036) and object 27958, miss distance 2 km at 2026-09-30 03:34:37 UTC',
    );
    expect(plain(alertView(named(null, null), 0).title)).toBe(
      'Object 57036 and object 27958, miss distance 2 km at 2026-09-30 03:34:37 UTC',
    );
    expect(plain(alertView(named(null, 'SL-12 DEB'), 0).title)).toBe(
      'Object 57036 and SL-12 DEB (27958), miss distance 2 km at 2026-09-30 03:34:37 UTC',
    );
  });
});
