import { TestBed } from '@angular/core/testing';

import { endLine, ListEnd, NOUNS, OLDER_IDLE, OlderState, olderStatus } from './list-end';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();
const loaded = (added: number, end: boolean): OlderState => ({
  phase: 'loaded',
  added,
  firstAdded: added ? 'x' : null,
  end,
  seq: 1,
});

describe('list end', () => {
  it('counts what is shown while older alerts exist, and names the whole stored list at the end', () => {
    expect(endLine(20, true, NOUNS.all)).toBe('20 shown, newest first');
    expect(endLine(43, false, NOUNS.all)).toBe('All 43 stored alerts are shown, newest first.');
    expect(endLine(3, false, NOUNS.close_approach)).toBe(
      'All 3 stored close approaches are shown, newest first.',
    );
    expect(endLine(7, false, NOUNS.space_weather_level)).toBe(
      'All 7 stored space weather alerts are shown, newest first.',
    );
    expect(endLine(1, false, NOUNS.close_approach)).toBe('The one stored close approach is shown.');
  });

  it('says where a Load older press stands, naming what the list holds', () => {
    const all = NOUNS.all;
    expect(olderStatus(OLDER_IDLE, all)).toBe('');
    expect(olderStatus({ ...OLDER_IDLE, phase: 'loading', seq: 1 }, all)).toBe(
      'Loading older alerts.',
    );
    expect(olderStatus(loaded(20, false), all)).toBe('20 older alerts loaded.');
    expect(olderStatus(loaded(1, false), all)).toBe('1 older alert loaded.');
    expect(olderStatus(loaded(3, true), all)).toBe(
      '3 older alerts loaded. No older alerts are stored.',
    );
    expect(olderStatus(loaded(0, true), all)).toBe('No older alerts are stored.');
    expect(olderStatus({ ...OLDER_IDLE, phase: 'failed', seq: 1 }, all)).toBe(
      'Older alerts could not be loaded. Use Load older to try again.',
    );
    expect(olderStatus(loaded(1, false), NOUNS.close_approach)).toBe(
      '1 older close approach loaded.',
    );
    expect(olderStatus(loaded(2, true), NOUNS.close_approach)).toBe(
      '2 older close approaches loaded. No older close approaches are stored.',
    );
    expect(olderStatus(loaded(1, true), NOUNS.space_weather_level)).toBe(
      '1 older space weather alert loaded. No older space weather alerts are stored.',
    );
    expect(olderStatus(loaded(5, false), NOUNS.space_weather_level)).toBe(
      '5 older space weather alerts loaded.',
    );
    expect(olderStatus({ ...OLDER_IDLE, phase: 'loading', seq: 1 }, NOUNS.close_approach)).toBe(
      'Loading older close approaches.',
    );
    expect(olderStatus({ ...OLDER_IDLE, phase: 'failed', seq: 1 }, NOUNS.space_weather_level)).toBe(
      'Older space weather alerts could not be loaded. Use Load older to try again.',
    );
  });

  async function mount(more: boolean, state: OlderState) {
    const fixture = TestBed.createComponent(ListEnd);
    fixture.componentRef.setInput('count', 20);
    fixture.componentRef.setInput('more', more);
    fixture.componentRef.setInput('noun', NOUNS.all);
    fixture.componentRef.setInput('state', state);
    const pressed: number[] = [];
    fixture.componentInstance.loadOlder.subscribe(() => pressed.push(1));
    await fixture.whenStable();
    return { el: fixture.nativeElement as HTMLElement, pressed };
  }

  it('sends nothing while a request is out, and marks the button disabled without removing it', async () => {
    const { el, pressed } = await mount(true, { ...OLDER_IDLE, phase: 'loading', seq: 1 });
    const button = el.querySelector('button')!;
    expect(button.getAttribute('aria-disabled')).toBe('true');
    button.click();
    expect(pressed).toEqual([]);
    expect(squash(el.querySelector('[role="status"]')?.textContent)).toBe('Loading older alerts.');
  });

  it('asks for older alerts on a press, and at the end shows a focusable end line instead of the button', async () => {
    const more = await mount(true, OLDER_IDLE);
    more.el.querySelector('button')!.click();
    expect(more.pressed).toEqual([1]);
    expect(more.el.querySelector('button')?.hasAttribute('aria-disabled')).toBe(false);

    const end = await mount(false, loaded(0, true));
    expect(end.el.querySelector('button')).toBeNull();
    expect(end.el.querySelector('.list-end-line')?.getAttribute('tabindex')).toBe('-1');
  });
});
