import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlSegmentGroup } from '@angular/router';

import closeApproach from '../../../schemas/alerts/examples/valid-close-approach.json';
import gLevel from '../../../schemas/alerts/examples/valid-g-level.json';
import rRestatement from '../../../schemas/alerts/examples/valid-r-restatement.json';
import screeningRun from '../../../schemas/alerts/examples/valid-screening-run.json';
import { routes } from './app.routes';

/** The event id the alert route takes from a URL, decoded as the Router decodes its path segments. */
function eventIdIn(router: Router, url: string): string | undefined {
  const primary: UrlSegmentGroup | undefined = router.parseUrl(url).root.children['primary'];
  const segments = primary?.segments ?? [];
  return segments.length === 2 && segments[0].path === 'alerts' ? segments[1].path : undefined;
}

describe('routes', () => {
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    router = TestBed.inject(Router);
  });

  // The committed examples cover each event id shape in docs/data/topics.md that has one, plus characters the
  // Router treats specially in a path ('(', ')', ';', '?', '#', '%', '&', '=', '+', a space).
  const IDS = [
    closeApproach.event_id,
    gLevel.event_id,
    rRestatement.event_id,
    screeningRun.event_id,
    'space_weather_level/1/R/18/2026-09-24T08:26:00Z/2026-09-30T19:18:36Z/refresh/2026-09-30T19:20:00Z',
    'odd/(a)/b;c=d?e#f%25g&h+i j',
  ];

  it('writes an event id as one path segment and reads the same id back', () => {
    for (const id of IDS) {
      const url = router.serializeUrl(router.createUrlTree(['/alerts', id]));
      expect(url.split('/').length).toBe(3);
      expect(url).not.toMatch(/[?#()]/);
      expect(eventIdIn(router, url)).toBe(id);
    }
  });

  it('writes the slashes as %2F and leaves the colons as they are', () => {
    expect(router.serializeUrl(router.createUrlTree(['/alerts', closeApproach.event_id]))).toBe(
      '/alerts/close_approach%2F1%2F2026-09-29T05:20:09Z%2F1%2F57036%2F27958%2F2026-09-30T03:34:37.588Z',
    );
  });

  it('reads the fully encoded form of the design (%3A for each colon) as the same id', () => {
    expect(
      eventIdIn(
        router,
        '/alerts/close_approach%2F1%2F2026-09-29T05%3A20%3A09Z%2F1%2F57036%2F27958%2F2026-09-30T03%3A34%3A37.588Z',
      ),
    ).toBe(closeApproach.event_id);
  });

  it('routes an alert id, an empty id, an object page and anything else', async () => {
    const at = async (url: string) => {
      await router.navigateByUrl(url);
      return router.routerState.snapshot.root.firstChild;
    };
    const page = await at(`/alerts/${encodeURIComponent(closeApproach.event_id)}`);
    expect(page?.routeConfig?.path).toBe('alerts/:eventId');
    expect(page?.paramMap.get('eventId')).toBe(closeApproach.event_id);
    expect((await at('/alerts'))?.routeConfig?.path).toBe('alerts');
    const object = await at('/objects/57036/alerts');
    expect(object?.paramMap.get('noradCatId')).toBe('57036');
    await router.navigateByUrl('/nowhere');
    expect(router.url).toBe('/');
  });
});
