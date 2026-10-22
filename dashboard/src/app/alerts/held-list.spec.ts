import { Alert } from '../data/queries';
import { ALERTS_ANONYMOUS } from '../../testing/fixtures';
import { holdList } from './held-list';

const RECORDED = ALERTS_ANONYMOUS.alerts.items as unknown as Alert[];
const alert = (id: string, from = 0): Alert => ({ ...RECORDED[from], event_id: id });
const ids = (items: Alert[]) => items.map((a) => a.event_id);

describe('holdList', () => {
  const frozen = [alert('a'), alert('b'), alert('c')];

  it('keeps the held alerts in place and counts what arrived above them', () => {
    const { items, held } = holdList(frozen, [alert('n2'), alert('n1'), alert('a'), alert('b')]);
    expect(ids(items)).toEqual(['a', 'b', 'c']);
    expect(ids(held)).toEqual(['n2', 'n1']);
  });

  it('never trims: an alert the data dropped off its page stays as it was shown', () => {
    const { items } = holdList(frozen, [alert('n1'), alert('a')]);
    expect(ids(items)).toEqual(['a', 'b', 'c']);
    expect(items[2]).toBe(frozen[2]);
  });

  it('never reorders, and holds an alert the data placed between held ones', () => {
    const { items, held } = holdList(frozen, [alert('b'), alert('x'), alert('a'), alert('c')]);
    expect(ids(items)).toEqual(['a', 'b', 'c']);
    expect(ids(held)).toEqual(['x']);
  });

  it('takes the newest data for a held alert, such as a new acknowledgement', () => {
    const acked = {
      ...alert('b'),
      acknowledgement: {
        action: 'acknowledge',
        acted_at: '2026-10-12T10:00:00Z',
        principal: null,
        note: null,
      },
    };
    const { items } = holdList(frozen, [alert('a'), acked, alert('c')]);
    expect(items[1]).toBe(acked);
  });

  it('adds an older page below the last held alert, since that moves nothing above it', () => {
    const { items, held } = holdList(frozen, [
      alert('a'),
      alert('b'),
      alert('c'),
      alert('d'),
      alert('e'),
    ]);
    expect(ids(items)).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(held).toEqual([]);
  });

  it('holds everything the data lists when none of the held alerts is in it', () => {
    const { items, held } = holdList(frozen, [alert('x'), alert('y')]);
    expect(ids(items)).toEqual(['a', 'b', 'c']);
    expect(ids(held)).toEqual(['x', 'y']);
  });
});
