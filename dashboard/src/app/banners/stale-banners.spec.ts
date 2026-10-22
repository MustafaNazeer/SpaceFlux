import { TestBed } from '@angular/core/testing';

import {
  SCREENING_CURRENT as SCREENING_QUERY,
  ScreeningCurrentData,
  SPACE_WEATHER_CURRENT,
  SpaceWeatherCurrentData,
} from '../data/queries';
import { parseUtc } from '../format';
import { dataOf } from '../../testing/graphql';
import {
  SCREENING_CURRENT,
  SCREENING_STALE,
  SPACE_WEATHER_MIXED,
  SPACE_WEATHER_NO_SERIES,
  SPACE_WEATHER_RECORDED,
} from '../../testing/fixtures';
import {
  BannerView,
  feedBanners,
  pausedBanner,
  refreshBanner,
  screeningBanner,
} from './banner-view';
import { StaleBanners, StaleLineMemory } from './stale-banners';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();
const weather = (root: object) =>
  dataOf<SpaceWeatherCurrentData>(SPACE_WEATHER_CURRENT, root).space_weather_current;
const screening = (root: object) =>
  dataOf<ScreeningCurrentData>(SCREENING_QUERY, root).screening_current;

/** A frame drawn, then the task after it: when a first visit fills in the set it starts from. */
const afterPaint = () =>
  new Promise<void>((resolve) => requestAnimationFrame(() => setTimeout(resolve)));

async function render(banners: BannerView[]): Promise<HTMLElement> {
  const fixture = TestBed.createComponent(StaleBanners);
  fixture.componentRef.setInput('banners', banners);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('StaleBanners', () => {
  it('shows one banner for the feed past its age limit, with its age, expected interval and what the page shows', async () => {
    const el = await render(feedBanners(weather(SPACE_WEATHER_MIXED)));
    const banners = el.querySelectorAll('.banner');
    expect(banners.length).toBe(1);
    expect(squash(banners[0].querySelector('.stale-word')?.textContent)).toBe('Stale, 55 min.');
    expect(squash(banners[0].textContent)).toBe(
      'Stale, 55 min. GOES-18 ≥10 MeV proton flux: newest record at 17:05 UTC, age limit 40 min. The S scale reads no data from 17:45 UTC until a current record arrives.',
    );
    expect(banners[0].querySelector('use')?.getAttribute('href')).toBe('#i-clock');
  });

  describe('on the first visit to the console in a page load', () => {
    const STALE_LINE = 'GOES-18 \u226510 MeV proton flux is stale; the S scale reads no data.';
    async function firstVisit(banners: BannerView[]) {
      const fixture = TestBed.createComponent(StaleBanners);
      fixture.componentRef.setInput('banners', banners);
      await fixture.whenStable();
      const el = fixture.nativeElement as HTMLElement;
      return { fixture, status: () => squash(el.querySelector('[role="status"]')?.textContent) };
    }

    it('draws the line empty, then speaks the stale set it starts from once a frame is drawn, as a direct load does', async () => {
      const { fixture, status } = await firstVisit(feedBanners(weather(SPACE_WEATHER_MIXED)));
      expect(status()).toBe('');
      await afterPaint();
      await fixture.whenStable();
      expect(status()).toBe(STALE_LINE);
    });

    it('stays silent when it starts from no banners', async () => {
      const { fixture, status } = await firstVisit([]);
      await afterPaint();
      await fixture.whenStable();
      expect(status()).toBe('');
    });

    it('does not speak the set again while only the ages change, and says when the notices clear', async () => {
      const stale = feedBanners(weather(SPACE_WEATHER_MIXED));
      const { fixture, status } = await firstVisit(stale);
      await afterPaint();
      await fixture.whenStable();
      const line = (fixture.nativeElement as HTMLElement).querySelector('[role="status"]')!;
      const node = line.firstChild;
      fixture.componentRef.setInput('banners', [{ ...stale[0], lead: 'Stale, 56 min.' }]);
      await fixture.whenStable();
      expect(line.firstChild).toBe(node);
      expect(status()).toBe(STALE_LINE);
      fixture.componentRef.setInput('banners', []);
      await fixture.whenStable();
      expect(status()).toBe('No stale notices remain.');
    });

    it('speaks a change made before the frame is drawn once, as the change it is', async () => {
      const { fixture, status } = await firstVisit([]);
      fixture.componentRef.setInput('banners', feedBanners(weather(SPACE_WEATHER_MIXED)));
      await fixture.whenStable();
      expect(status()).toBe(STALE_LINE);
      const line = (fixture.nativeElement as HTMLElement).querySelector('[role="status"]')!;
      const node = line.firstChild;
      await afterPaint();
      await fixture.whenStable();
      expect(line.firstChild).toBe(node);
    });

    it('leaves the line as it was spoken when the set clears before the frame is drawn', async () => {
      const { fixture, status } = await firstVisit(feedBanners(weather(SPACE_WEATHER_MIXED)));
      fixture.componentRef.setInput('banners', []);
      await fixture.whenStable();
      expect(status()).toBe('No stale notices remain.');
      await afterPaint();
      await fixture.whenStable();
      expect(status()).toBe('No stale notices remain.');
    });

    it('remembers an empty line when the console is left before the frame is drawn', async () => {
      const { fixture } = await firstVisit(feedBanners(weather(SPACE_WEATHER_MIXED)));
      fixture.destroy();
      await afterPaint();
      expect(TestBed.inject(StaleLineMemory).left?.announcement.text).toBe('');
    });

    it('starts from the set without speaking it on a return after that visit', async () => {
      const stale = feedBanners(weather(SPACE_WEATHER_MIXED));
      const first = await firstVisit(stale);
      await afterPaint();
      await first.fixture.whenStable();
      first.fixture.destroy();
      const back = await firstVisit([{ ...stale[0], lead: 'Stale, 70 min.' }]);
      await afterPaint();
      await back.fixture.whenStable();
      expect(back.status()).toBe('');
      back.fixture.componentRef.setInput('banners', []);
      await back.fixture.whenStable();
      expect(back.status()).toBe('No stale notices remain.');
    });
  });

  describe('on a return to the console', () => {
    async function shown(banners: BannerView[]) {
      const fixture = TestBed.createComponent(StaleBanners);
      fixture.componentRef.setInput('banners', banners);
      await fixture.whenStable();
      const el = fixture.nativeElement as HTMLElement;
      return {
        fixture,
        status: () => squash(el.querySelector('[role="status"]')?.textContent),
      };
    }
    const stale = () => feedBanners(weather(SPACE_WEATHER_MIXED));

    it('stays silent when the set is the one shown when the console was left', async () => {
      const left = await shown(stale());
      left.fixture.destroy();
      const back = await shown([{ ...stale()[0], lead: 'Stale, 70 min.' }]);
      expect(back.status()).toBe('');
      await afterPaint();
      await back.fixture.whenStable();
      expect(back.status()).toBe('');
    });

    it('speaks a set that changed while away', async () => {
      const left = await shown([]);
      left.fixture.destroy();
      const back = await shown(stale());
      expect(back.status()).toBe(
        'GOES-18 \u226510 MeV proton flux is stale; the S scale reads no data.',
      );
    });

    it('says the stale notices cleared while away', async () => {
      const left = await shown(stale());
      left.fixture.destroy();
      const back = await shown([]);
      expect(back.status()).toBe('No stale notices remain.');
    });

    it('compares with the set at the latest leave, not an older one', async () => {
      const first = await shown(stale());
      first.fixture.destroy();
      const second = await shown(stale());
      second.fixture.componentRef.setInput('banners', []);
      await second.fixture.whenStable();
      second.fixture.destroy();
      const back = await shown([]);
      expect(back.status()).toBe('');
    });
  });

  it('keeps the visible banners out of the live region, which speaks only when the set of stale feeds changes', async () => {
    const fixture = TestBed.createComponent(StaleBanners);
    const set = async (banners: BannerView[]) => {
      fixture.componentRef.setInput('banners', banners);
      await fixture.whenStable();
    };
    const el = fixture.nativeElement as HTMLElement;
    const status = () => el.querySelector('[role="status"]')!;
    await set([]);
    expect(el.querySelector('section.banners')?.getAttribute('role')).toBeNull();
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(status().classList).toContain('visually-hidden');
    expect(status().textContent?.trim()).toBe('');

    const stale = feedBanners(weather(SPACE_WEATHER_MIXED));
    await set(stale);
    const node = status().firstChild;
    expect(squash(status().textContent)).toBe(
      'GOES-18 \u226510 MeV proton flux is stale; the S scale reads no data.',
    );

    // A minute later the age in the visible banner changes; the status text, and its text node, do not.
    await set([{ ...stale[0], lead: 'Stale, 56 min.' }]);
    expect(squash(el.querySelector('.banner .stale-word')?.textContent)).toBe('Stale, 56 min.');
    expect(status().firstChild).toBe(node);
    expect(squash(status().textContent)).toBe(
      'GOES-18 \u226510 MeV proton flux is stale; the S scale reads no data.',
    );

    await set([]);
    expect(squash(status().textContent)).toBe('No stale notices remain.');
  });

  it('shows no feed banner while every feed is fresh, or for a scale that never had a series', () => {
    expect(feedBanners(weather(SPACE_WEATHER_RECORDED))).toEqual([]);
    expect(feedBanners(weather(SPACE_WEATHER_NO_SERIES))).toEqual([]);
    expect(feedBanners(undefined)).toEqual([]);
  });

  it('shows a banner for a run the API marks stale, aged from its window start', async () => {
    expect(
      screeningBanner(screening(SCREENING_CURRENT), parseUtc('2026-09-30T12:20:09Z')),
    ).toBeNull();
    const banner = screeningBanner(screening(SCREENING_STALE), parseUtc('2026-09-30T12:20:09Z'));
    const el = await render([banner!]);
    expect(squash(el.querySelector('.banners')?.textContent)).toBe(
      "Stale, 31 h. Screening run 2026-09-29T05:20:09Z/1 is more than 24 h past its window start or past its window end; its approaches come from that run's element sets, not the current catalog.",
    );
  });

  describe('refresh banner', () => {
    const AS_OF = '2026-10-04T18:00:00.000000Z';
    const T1 = parseUtc('2026-10-04T18:01:00Z');
    const T2 = parseUtc('2026-10-04T18:01:30Z');
    const textOf = async (banner: BannerView | null) =>
      squash((await render([banner!])).querySelector('.banners')?.textContent);

    it('shows nothing while every query refreshed', () => {
      expect(refreshBanner({}, AS_OF)).toBeNull();
    });

    it('names one failed part, says "it", and adds when the space weather levels were received', async () => {
      const banner = refreshBanner({ spaceWeather: T1 }, AS_OF);
      expect(await textOf(banner)).toBe(
        'Not updating. A refresh of the space weather levels failed at 18:01:00 UTC. What is shown for it is from the last refresh that succeeded. The space weather levels were received at 18:00:00 UTC.',
      );
      expect(banner!.announce).toBe('A refresh of the space weather levels failed.');
    });

    it('leaves out the received sentence when the space weather levels never arrived', async () => {
      expect(await textOf(refreshBanner({ spaceWeather: T1 }, undefined))).toBe(
        'Not updating. A refresh of the space weather levels failed at 18:01:00 UTC. What is shown for it is from the last refresh that succeeded.',
      );
    });

    it('joins two parts with "and", says "them", uses the newest failure, and adds no received sentence without space weather', async () => {
      const banner = refreshBanner({ alerts: T1, screening: T2 }, AS_OF);
      expect(await textOf(banner)).toBe(
        'Not updating. A refresh of the screening run and recent alerts failed at 18:01:30 UTC. What is shown for them is from the last refresh that succeeded.',
      );
      expect(banner!.announce).toBe('A refresh of the screening run and recent alerts failed.');
    });

    it('lists three parts in page order', async () => {
      const banner = refreshBanner({ alerts: T1, screening: T1, spaceWeather: T2 }, AS_OF);
      expect(await textOf(banner)).toBe(
        'Not updating. A refresh of the space weather levels, the screening run and recent alerts failed at 18:01:30 UTC. What is shown for them is from the last refresh that succeeded. The space weather levels were received at 18:00:00 UTC.',
      );
      expect(banner!.announce).toBe(
        'A refresh of the space weather levels, the screening run and recent alerts failed.',
      );
    });
  });

  describe('paused banner', () => {
    const AS_OF = '2026-10-04T18:00:00.000000Z';
    const textOf = async (banner: BannerView | null) =>
      squash((await render([banner!])).querySelector('.banners')?.textContent);

    it('shows nothing while updates run', () => {
      expect(pausedBanner(false, AS_OF, {})).toBeNull();
    });

    it('names when the space weather levels were received', async () => {
      const banner = pausedBanner(true, AS_OF, {});
      expect(await textOf(banner)).toBe(
        'Paused. Updates are paused. The space weather levels below were received at 18:00:00 UTC. Nothing below is checked against the age limits again until updates resume.',
      );
      expect(banner!.announce).toBe('Updates are paused.');
    });

    it('says nothing has been received yet', async () => {
      expect(await textOf(pausedBanner(true, undefined, {}))).toBe(
        'Paused. Updates are paused. Nothing has been received yet.',
      );
    });

    it('adds the refreshes that failed before the pause', async () => {
      const banner = pausedBanner(true, AS_OF, {
        spaceWeather: parseUtc('2026-10-04T18:01:00Z'),
        alerts: parseUtc('2026-10-04T18:01:30Z'),
      });
      expect(await textOf(banner)).toBe(
        'Paused. Updates are paused. The space weather levels below were received at 18:00:00 UTC. Nothing below is checked against the age limits again until updates resume. A refresh of the space weather levels and recent alerts failed at 18:01:30 UTC before the pause.',
      );
      expect(banner!.announce).toBe('Updates are paused.');
      expect(
        await textOf(
          pausedBanner(true, undefined, { screening: parseUtc('2026-10-04T18:01:00Z') }),
        ),
      ).toBe(
        'Paused. Updates are paused. Nothing has been received yet. A refresh of the screening run failed at 18:01:00 UTC before the pause.',
      );
    });
  });

  describe('status line while paused', () => {
    const paused = pausedBanner(true, '2026-10-04T18:00:00.000000Z', {})!;
    const refresh = refreshBanner(
      { spaceWeather: parseUtc('2026-10-04T18:01:00Z') },
      '2026-10-04T18:00:00.000000Z',
    )!;
    const FAILED = 'A refresh of the space weather levels failed.';

    function setup() {
      const fixture = TestBed.createComponent(StaleBanners);
      const el = fixture.nativeElement as HTMLElement;
      return {
        set: async (banners: BannerView[]) => {
          fixture.componentRef.setInput('banners', banners);
          await fixture.whenStable();
        },
        status: () => el.querySelector('[role="status"]')!,
      };
    }

    it('says "Updates are paused." once, and not again while the banners below it change', async () => {
      const { set, status } = setup();
      const stale = feedBanners(weather(SPACE_WEATHER_MIXED));
      await set(stale);
      await set([paused, ...stale]);
      expect(squash(status().textContent)).toBe('Updates are paused.');
      const node = status().firstChild;
      await set([paused, { ...stale[0], lead: 'Stale, 56 min.' }]);
      await set([paused]);
      expect(status().firstChild).toBe(node);
      expect(squash(status().textContent)).toBe('Updates are paused.');
    });

    it('does not announce a recovery on pause or on resume, only once the stale notices clear after it', async () => {
      const { set, status } = setup();
      await set([]);
      await set([refresh]);
      expect(squash(status().textContent)).toBe(FAILED);
      await set([paused]);
      expect(squash(status().textContent)).toBe('Updates are paused.');
      // Resumed: the failed refresh still stands until a refresh succeeds.
      await set([refresh]);
      expect(squash(status().textContent)).toBe(FAILED);
      await set([]);
      expect(squash(status().textContent)).toBe('No stale notices remain.');
    });

    it('announces the recovery on resume when a refresh in flight at the pause succeeded meanwhile', async () => {
      const { set, status } = setup();
      await set([refresh]);
      await set([paused]);
      await set([]);
      expect(squash(status().textContent)).toBe('No stale notices remain.');
    });

    it('stays quiet on resume after a recovery that was already announced before the pause', async () => {
      const { set, status } = setup();
      await set(feedBanners(weather(SPACE_WEATHER_MIXED)));
      await set([]);
      expect(squash(status().textContent)).toBe('No stale notices remain.');
      await set([paused]);
      expect(squash(status().textContent)).toBe('Updates are paused.');
      await set([]);
      expect(squash(status().textContent)).toBe('');
    });

    it('stays quiet on resume when nothing was stale before the pause', async () => {
      const { set, status } = setup();
      await set([]);
      await set([paused]);
      expect(squash(status().textContent)).toBe('Updates are paused.');
      await set([]);
      expect(squash(status().textContent)).toBe('');
    });
  });
});
