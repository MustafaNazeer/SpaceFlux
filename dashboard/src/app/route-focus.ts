import { afterNextRender, Injectable, Injector } from '@angular/core';

/** How many pages remember the link that left them; the oldest is forgotten first. */
const REMEMBERED = 50;

interface Ready {
  root: HTMLElement;
  injector: Injector;
}

/**
 * Where focus goes after an in app page change. The new page tells this service when it has settled (its first load
 * answered, failed or was not needed), and only then does focus move, so a screen reader starts on the page's real
 * title rather than on a heading that is about to change. On a history navigation back to a page, the link
 * that left it takes focus if it is still there, matched by its href, else the h1. Focus never scrolls; a
 * history navigation keeps its own scroll position, except that a returned link outside the window is brought just
 * into view. A page no longer in the document never takes or releases the focus a change waits for.
 */
@Injectable({ providedIn: 'root' })
export class RouteFocus {
  /** The href of the link that had focus when each page (by URL) was left. */
  private readonly leftBy = new Map<string, string>();
  private navigating = false;
  /** A page settled while its navigation was still running. */
  private ready: Ready | null = null;
  /** A page change finished and waits for its page to settle; `href` is the link to return to. */
  private pending: { href: string | null } | null = null;

  /** A navigation starts away from `url`, with `active` focused. */
  start(url: string, active: Element | null): void {
    this.navigating = true;
    this.ready = null;
    this.pending = null;
    const href = active instanceof HTMLAnchorElement ? active.getAttribute('href') : null;
    this.leftBy.delete(url);
    if (href) {
      this.leftBy.set(url, href);
      if (this.leftBy.size > REMEMBERED) {
        this.leftBy.delete(this.leftBy.keys().next().value!);
      }
    }
  }

  /**
   * The navigation ended at `url`. Focus moves only for a change of page after the first load; `restoring` is a
   * history navigation, which returns to the link that left the page.
   */
  end(url: string, pageChanged: boolean, restoring: boolean): void {
    this.navigating = false;
    // A page that settled while the navigation ran but has since been replaced was the page being left.
    const ready = this.ready?.root.isConnected ? this.ready : null;
    this.ready = null;
    if (!pageChanged) {
      return;
    }
    const href = restoring ? (this.leftBy.get(url) ?? null) : null;
    if (ready) {
      this.focus(ready, href);
    } else {
      this.pending = { href };
    }
  }

  /** A navigation was cancelled or failed: nothing waits for it. */
  abandon(): void {
    this.navigating = false;
    this.ready = null;
  }

  /** The page in `root` has settled; focus moves to it now if a page change is waiting for it. */
  settled(root: HTMLElement, injector: Injector): void {
    // While a navigation runs, the new page may settle before it is attached, so it is kept and checked at the end.
    if (this.navigating) {
      this.ready = { root, injector };
      return;
    }
    if (!root.isConnected) {
      return;
    }
    const pending = this.pending;
    this.pending = null;
    if (pending) {
      this.focus({ root, injector }, pending.href);
    }
  }

  private focus({ root, injector }: Ready, href: string | null): void {
    afterNextRender(
      () => {
        const link =
          href === null
            ? undefined
            : Array.from(root.querySelectorAll<HTMLElement>('a[href]')).find(
                (a) => a.getAttribute('href') === href,
              );
        if (!link) {
          root.querySelector<HTMLElement>('h1')?.focus({ preventScroll: true });
          return;
        }
        link.focus({ preventScroll: true });
        // The browser restores the scroll position before the page is drawn again, so it can leave the link out
        // of view.
        const { top, bottom } = link.getBoundingClientRect();
        if (top < 0 || bottom > window.innerHeight) {
          link.scrollIntoView({ block: 'nearest', behavior: 'instant' });
        }
      },
      { injector },
    );
  }
}
