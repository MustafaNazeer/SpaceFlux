import { ScreeningCurrentData, SpaceWeatherCurrentData } from '../data/queries';
import { at, duration, hm, hms, parseUtc, Segment, text } from '../format';
import { measurementName } from '../scales';

export interface BannerView {
  key: string;
  lead: string;
  body: Segment[];
  /** What the status announcement says while this banner shows; it changes only when the set of banners does. */
  announce: string;
}

/** One banner per scale whose feed is past its age limit (docs/design/tokens.md, banner-stale). */
export function feedBanners(
  current: SpaceWeatherCurrentData['space_weather_current'] | undefined,
): BannerView[] {
  if (!current) {
    return [];
  }
  const asOf = parseUtc(current.as_of);
  const out: BannerView[] = [];
  for (const s of current.scales) {
    const fresh = parseUtc(s.freshness_reference);
    if (s.state !== 'no_data' || s.no_data_reason !== 'age_limit' || Number.isNaN(fresh)) {
      continue;
    }
    const body: Segment[] = [
      text(` ${measurementName(s.scale, s.satellite)}: newest record at `),
      at(fresh, hm, ' UTC'),
    ];
    if (s.age_limit_s !== null) {
      body.push(text(`, age limit ${duration(s.age_limit_s * 1000)}`));
    }
    const since = parseUtc(s.no_data_since);
    if (Number.isNaN(since)) {
      body.push(text(`. The ${s.scale} scale reads no data until a current record arrives.`));
    } else {
      body.push(
        text(`. The ${s.scale} scale reads no data from `),
        at(since, hm, ' UTC'),
        text(' until a current record arrives.'),
      );
    }
    out.push({
      key: `scale-${s.scale}`,
      lead: `Stale, ${duration(asOf - fresh)}.`,
      body,
      announce: `${measurementName(s.scale, s.satellite)} is stale; the ${s.scale} scale reads no data.`,
    });
  }
  return out;
}

/** A banner for a screening run the API marks stale, aged from its window start. */
export function screeningBanner(
  current: ScreeningCurrentData['screening_current'] | undefined,
  nowMs: number,
): BannerView | null {
  if (!current?.stale) {
    return null;
  }
  const start = parseUtc(current.summary.window_start);
  return {
    key: 'screening',
    lead: `Stale, ${duration(nowMs - start)}.`,
    body: [
      text(
        ` Screening run ${current.summary.run_id} is more than 24 h past its window start or past its window end; its approaches come from that run's element sets, not the current catalog.`,
      ),
    ],
    announce: 'The screening run is stale.',
  };
}

/** Browser time of each query's newest failed refresh, absent while its refreshes succeed. */
export interface Failures {
  spaceWeather?: number;
  screening?: number;
  alerts?: number;
}

const PARTS: [keyof Failures, string][] = [
  ['spaceWeather', 'the space weather levels'],
  ['screening', 'the screening run'],
  ['alerts', 'recent alerts'],
];

/** The failed parts in page order ("X", "X and Y", "X, Y and Z"), how many, and the newest failure time. */
function failed(failures: Failures): { parts: string; count: number; at: number } | null {
  const hit = PARTS.filter(([k]) => failures[k] !== undefined);
  if (!hit.length) {
    return null;
  }
  const names = hit.map(([, name]) => name);
  const parts =
    names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`;
  return { parts, count: names.length, at: Math.max(...hit.map(([k]) => failures[k]!)) };
}

/** A banner when a query's newest refresh failed, so its kept values are not read as current. */
export function refreshBanner(failures: Failures, lastAsOf: string | undefined): BannerView | null {
  const f = failed(failures);
  if (!f) {
    return null;
  }
  const body: Segment[] = [
    text(` A refresh of ${f.parts} failed at `),
    at(f.at, hms, ' UTC'),
    text(
      `. What is shown for ${f.count === 1 ? 'it' : 'them'} is from the last refresh that succeeded.`,
    ),
  ];
  const last = parseUtc(lastAsOf);
  if (failures.spaceWeather !== undefined && !Number.isNaN(last)) {
    body.push(
      text(' The space weather levels were received at '),
      at(last, hms, ' UTC'),
      text('.'),
    );
  }
  return {
    key: 'refresh',
    lead: 'Not updating.',
    body,
    announce: `A refresh of ${f.parts} failed.`,
  };
}

/** The key of the paused banner, which always shows first. */
export const PAUSED_KEY = 'paused';

/** A banner while updates are paused: nothing shown is refreshed or checked against the age limits. */
export function pausedBanner(
  paused: boolean,
  lastAsOf: string | undefined,
  failures: Failures,
): BannerView | null {
  if (!paused) {
    return null;
  }
  const last = parseUtc(lastAsOf);
  const body: Segment[] = Number.isNaN(last)
    ? [text(' Updates are paused. Nothing has been received yet.')]
    : [
        text(' Updates are paused. The space weather levels below were received at '),
        at(last, hms, ' UTC'),
        text('. Nothing below is checked against the age limits again until updates resume.'),
      ];
  const f = failed(failures);
  if (f) {
    body.push(
      text(` A refresh of ${f.parts} failed at `),
      at(f.at, hms, ' UTC'),
      text(' before the pause.'),
    );
  }
  return { key: PAUSED_KEY, lead: 'Paused.', body, announce: 'Updates are paused.' };
}

/** What a banner contributes to the status line. */
export type Spoken = Pick<BannerView, 'key' | 'announce'>;

export const RECOVERED = 'No stale notices remain.';

/** The status line, and whether a stale notice was the last thing announced while updates were running. */
export interface Announcement {
  text: string;
  wasStale: boolean;
}

/**
 * The status announcement for the current banners. While paused it says only the paused line, once, and
 * remembers whether a stale notice was showing before the pause. Otherwise it says the joined announce lines
 * of the banners, then "No stale notices remain." once the last one clears, and nothing before anything went
 * stale. Every stale banner clears only on a successful refresh, so a recovery is never announced unless a
 * refresh has succeeded.
 */
export function announcement(banners: Spoken[], previous: Announcement | undefined): Announcement {
  const wasStale = previous?.wasStale ?? false;
  const paused = banners.find((b) => b.key === PAUSED_KEY);
  if (paused) {
    return { text: paused.announce, wasStale };
  }
  const lines = banners.map((b) => b.announce).join(' ');
  if (lines) {
    return { text: lines, wasStale: true };
  }
  return { text: wasStale ? RECOVERED : '', wasStale: false };
}
