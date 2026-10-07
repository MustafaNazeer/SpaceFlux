import { SuppressedPair } from '../data/queries';
import { listEntry, suppressionReason } from './screening';

const pair = (over: Partial<SuppressedPair>): SuppressedPair => ({
  watchlist_number: 25544,
  watchlist_name: 'ISS (ZARYA)',
  other_number: 49044,
  other_name: null,
  mechanism: 'static_stack',
  stack_name: null,
  detail: 'not screened for close approaches',
  min_separation_m: 41.2,
  max_separation_m: 58.9,
  stack_entry_may_be_stale: false,
  ...over,
});

describe('suppression reasons', () => {
  it('names a station stack pair, with the stack name when the summary carries one', () => {
    expect(suppressionReason(pair({}), 5000, '5 km')).toBe('Listed in the same station stack');
    expect(
      suppressionReason(pair({ stack_name: 'International Space Station' }), 5000, '5 km'),
    ).toBe('Listed in the same station stack, International Space Station');
  });

  it('says a co orbiting pair is inferred, and when its sampled minimum is within the report distance', () => {
    const label =
      'Moved together over the whole window (inferred from GP data, not known to be attached)';
    expect(
      suppressionReason(pair({ mechanism: 'co_orbiting', min_separation_m: 6000 }), 5000, '5 km'),
    ).toBe(label);
    expect(
      suppressionReason(pair({ mechanism: 'co_orbiting', min_separation_m: 5000 }), 5000, '5 km'),
    ).toBe(`${label}. Sampled minimum within 5 km; approaches for this pair were not computed.`);
  });

  it('keeps identical element sets and falls back to the detail text for an unknown code', () => {
    expect(suppressionReason(pair({ mechanism: 'same_elements' }), 5000, '5 km')).toBe(
      'Identical element sets',
    );
    expect(suppressionReason(pair({ mechanism: 'new_code', detail: 'why' }), 5000, '5 km')).toBe(
      'why',
    );
  });

  it('fills the list entry column only for station stack pairs', () => {
    expect(listEntry(pair({}))).toBe('Not flagged');
    expect(listEntry(pair({ stack_entry_may_be_stale: true }))).toBe('May be stale');
    expect(listEntry(pair({ mechanism: 'co_orbiting' }))).toBe('No list entry');
  });
});
