import { TestBed } from '@angular/core/testing';

import { IconSprite } from './icon-sprite';

describe('IconSprite', () => {
  it('defines every icon the components reference, hidden from assistive technology', async () => {
    const fixture = TestBed.createComponent(IconSprite);
    await fixture.whenStable();
    const svg = (fixture.nativeElement as HTMLElement).querySelector('svg')!;
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    const ids = Array.from(svg.querySelectorAll('symbol')).map((s) => s.id);
    expect(ids).toEqual([
      'i-none',
      'i-l1',
      'i-l2',
      'i-l3',
      'i-l4',
      'i-l5',
      'i-nodata',
      'i-clock',
      'i-approach',
      'i-check',
      'i-clipped',
      'i-open',
    ]);
  });
});
