import { TestBed } from '@angular/core/testing';

import { levelBadge, NO_DATA_BADGE, stateBadge, staleBadge } from '../scales';
import { Badge } from './badge';

describe('Badge', () => {
  it('carries its icon shape and text, so it never depends on color alone', async () => {
    const fixture = TestBed.createComponent(Badge);
    fixture.componentRef.setInput('badge', levelBadge('G', 5, 'solid'));
    await fixture.whenStable();
    const span = (fixture.nativeElement as HTMLElement).querySelector('.badge')!;
    expect([...span.classList].sort()).toEqual(['badge', 'l5', 'solid']);
    expect(span.textContent?.trim()).toBe('G5 Extreme');
    expect(span.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(span.querySelector('use')?.getAttribute('href')).toBe('#i-l5');
  });

  it('never guesses a level: a level state without a valid level number reads as no data', () => {
    expect(stateBadge('R', 'level', null, 'solid')).toEqual(NO_DATA_BADGE);
    expect(stateBadge('R', 'level', 6, 'solid')).toEqual(NO_DATA_BADGE);
    expect(stateBadge('R', 'ended', null, 'solid')).toEqual(NO_DATA_BADGE);
    expect(staleBadge('14 min').text).toBe('Stale, 14 min');
  });
});
