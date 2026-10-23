import { ApplicationRef, Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { RouteFocus } from './route-focus';

describe('RouteFocus', () => {
  let focus: RouteFocus;
  let injector: Injector;
  const roots: HTMLElement[] = [];

  beforeEach(() => {
    focus = TestBed.inject(RouteFocus);
    injector = TestBed.inject(Injector);
  });

  afterEach(() => {
    roots.splice(0).forEach((r) => r.remove());
  });

  /** A page in the document with an h1 and one link per href. */
  function page(...hrefs: string[]): HTMLElement {
    const root = document.createElement('div');
    const h1 = document.createElement('h1');
    h1.tabIndex = -1;
    root.append(h1);
    for (const href of hrefs) {
      const a = document.createElement('a');
      a.setAttribute('href', href);
      root.append(a);
    }
    document.body.append(root);
    roots.push(root);
    return root;
  }

  const link = (root: HTMLElement, href: string) =>
    root.querySelector<HTMLAnchorElement>(`a[href="${href}"]`)!;
  const render = () => TestBed.inject(ApplicationRef).tick();

  /** Leaves `url` with the link to `href` focused, as a click on it would. */
  function leave(url: string, from: HTMLElement, href: string): void {
    link(from, href).focus();
    focus.start(url, document.activeElement);
    focus.abandon();
  }

  /** A history navigation back to `url`, whose page settles in `root`. */
  function back(url: string, root: HTMLElement): void {
    focus.start('/elsewhere', null);
    focus.end(url, true, true);
    focus.settled(root, injector);
    render();
  }

  it('moves focus to the h1 once the new page has settled after its navigation ended', () => {
    const next = page();
    focus.start('/', null);
    focus.end('/b', true, false);
    render();
    expect(document.activeElement).toBe(document.body);
    const h1Focus = vi.spyOn(next.querySelector('h1')!, 'focus');
    focus.settled(next, injector);
    render();
    expect(document.activeElement).toBe(next.querySelector('h1'));
    expect(h1Focus).toHaveBeenCalledWith({ preventScroll: true });
  });

  it('forgets a page change still waiting for its page once another navigation starts', () => {
    const root = page();
    focus.start('/', null);
    focus.end('/b', true, false);
    focus.start('/b', null);
    focus.abandon();
    focus.settled(root, injector);
    render();
    expect(document.activeElement).toBe(document.body);
  });

  it('never lets the page being left take the focus meant for the new one', () => {
    const old = page('/x');
    focus.start('/a', null);
    // The old page's own load answers after the navigation started but before the new page replaced it.
    focus.settled(old, injector);
    old.remove();
    const next = page();
    focus.end('/b', true, false);
    render();
    expect(document.activeElement).toBe(document.body);
    focus.settled(next, injector);
    render();
    expect(document.activeElement).toBe(next.querySelector('h1'));
  });

  it('ignores a page that settles once it is no longer in the document', () => {
    const old = page('/x');
    focus.start('/a', null);
    old.remove();
    const next = page();
    focus.end('/b', true, false);
    focus.settled(old, injector);
    render();
    expect(document.activeElement).toBe(document.body);
    focus.settled(next, injector);
    render();
    expect(document.activeElement).toBe(next.querySelector('h1'));
  });

  it('after a cancelled navigation moves no focus when a page settles', () => {
    const root = page('/x');
    focus.start('/a', null);
    focus.abandon();
    focus.settled(root, injector);
    render();
    expect(document.activeElement).toBe(document.body);
  });

  it('returns to the link that left a page, else to its h1', () => {
    const from = page('/to/1');
    leave('/p/1', from, '/to/1');
    link(from, '/to/1').blur();
    back('/p/1', from);
    expect(document.activeElement).toBe(link(from, '/to/1'));
    back('/p/2', from);
    expect(document.activeElement).toBe(from.querySelector('h1'));
  });

  it('remembers the link for the 50 pages left most recently, forgetting the oldest first', () => {
    const hrefs = Array.from({ length: 52 }, (_, i) => `/to/${i}`);
    const root = page(...hrefs);
    for (let i = 0; i < 50; i++) {
      leave(`/p/${i}`, root, `/to/${i}`);
    }
    // Leaving page 0 again makes it the most recent, so page 1 is now the oldest.
    leave('/p/0', root, '/to/50');
    leave('/p/51', root, '/to/51');

    back('/p/1', root);
    expect(document.activeElement).toBe(root.querySelector('h1'));
    back('/p/0', root);
    expect(document.activeElement).toBe(link(root, '/to/50'));
    back('/p/2', root);
    expect(document.activeElement).toBe(link(root, '/to/2'));
    back('/p/51', root);
    expect(document.activeElement).toBe(link(root, '/to/51'));
  });

  describe('bringing the returned link into view', () => {
    function at(top: number): { root: HTMLElement; scroll: ReturnType<typeof vi.fn> } {
      const root = page('/to/1');
      const a = link(root, '/to/1');
      vi.spyOn(a, 'getBoundingClientRect').mockReturnValue({
        top,
        bottom: top + 20,
      } as DOMRect);
      const scroll = vi.fn();
      a.scrollIntoView = scroll;
      leave('/p/1', root, '/to/1');
      a.blur();
      return { root, scroll };
    }

    it('scrolls it into view, nearest and at once, when it is above the window', () => {
      const { root, scroll } = at(-1209);
      const focusSpy = vi.spyOn(link(root, '/to/1'), 'focus');
      back('/p/1', root);
      expect(document.activeElement).toBe(link(root, '/to/1'));
      expect(focusSpy).toHaveBeenCalledWith({ preventScroll: true });
      expect(scroll).toHaveBeenCalledExactlyOnceWith({ block: 'nearest', behavior: 'instant' });
    });

    it('scrolls it into view when it is below the window', () => {
      const { root, scroll } = at(window.innerHeight + 300);
      back('/p/1', root);
      expect(scroll).toHaveBeenCalledExactlyOnceWith({ block: 'nearest', behavior: 'instant' });
    });

    describe('once a later load has moved the page', () => {
      /** The link moves to `top` after it was focused, as when a list above it is drawn. */
      function moveTo(root: HTMLElement, top: number): void {
        vi.mocked(link(root, '/to/1').getBoundingClientRect).mockReturnValue({
          top,
          bottom: top + 20,
        } as DOMRect);
      }

      it('brings the returned link just into view again when it still has focus', () => {
        const { root, scroll } = at(200);
        back('/p/1', root);
        moveTo(root, window.innerHeight + 200);
        focus.reveal(injector);
        render();
        expect(scroll).toHaveBeenCalledExactlyOnceWith({ block: 'nearest', behavior: 'instant' });
      });

      it('leaves the scroll position alone when the link is still inside the window', () => {
        const { root, scroll } = at(200);
        back('/p/1', root);
        moveTo(root, 300);
        focus.reveal(injector);
        render();
        expect(scroll).not.toHaveBeenCalled();
      });

      it('does nothing once focus has moved off the returned link', () => {
        const { root, scroll } = at(200);
        back('/p/1', root);
        root.querySelector<HTMLElement>('h1')!.focus();
        moveTo(root, window.innerHeight + 200);
        focus.reveal(injector);
        render();
        expect(scroll).not.toHaveBeenCalled();
        expect(document.activeElement).toBe(root.querySelector('h1'));
      });

      it('does nothing for a page reached by a new navigation, where the h1 took focus', () => {
        const { root, scroll } = at(200);
        focus.start('/elsewhere', null);
        focus.end('/p/1', true, false);
        focus.settled(root, injector);
        render();
        link(root, '/to/1').focus();
        moveTo(root, window.innerHeight + 200);
        focus.reveal(injector);
        render();
        expect(scroll).not.toHaveBeenCalled();
      });

      it('forgets the returned link once another navigation starts', () => {
        const { root, scroll } = at(200);
        back('/p/1', root);
        focus.start('/p/1', document.activeElement);
        moveTo(root, window.innerHeight + 200);
        focus.reveal(injector);
        render();
        expect(scroll).not.toHaveBeenCalled();
      });

      it('does nothing when the page has left the document', () => {
        const { root, scroll } = at(200);
        back('/p/1', root);
        moveTo(root, window.innerHeight + 200);
        root.remove();
        focus.reveal(injector);
        render();
        expect(scroll).not.toHaveBeenCalled();
      });
    });

    it('leaves the scroll position alone when the link is inside the window', () => {
      const { root, scroll } = at(200);
      back('/p/1', root);
      expect(document.activeElement).toBe(link(root, '/to/1'));
      expect(scroll).not.toHaveBeenCalled();
    });
  });
});
