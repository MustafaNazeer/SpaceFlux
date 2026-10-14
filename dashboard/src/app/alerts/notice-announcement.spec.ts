import { ALERT_STORED, AlertStoredData } from '../data/queries';
import { Segment } from '../format';
import { dataOf } from '../../testing/graphql';
import { ALERTS_ANONYMOUS, ALERTS_ENDED } from '../../testing/fixtures';
import { noticeAlert } from './alert-list';
import { NOUNS } from './list-end';
import {
  awayAnnouncement,
  groupAnnouncement,
  heldAnnouncement,
  noticeAnnouncement,
  pausedAnnouncement,
} from './notice-announcement';

const plain = (segments: Segment[]) =>
  segments.map((s) => (s.kind === 'text' ? s.text : s.kind === 'time' ? s.label : s.unit)).join('');

function say(event: Record<string, unknown>): string {
  const { acknowledgement: _, ...alert } = event;
  const notice = dataOf<AlertStoredData>(ALERT_STORED, { alert_stored: alert }).alert_stored;
  return plain(noticeAnnouncement(noticeAlert(notice)));
}

const items = ALERTS_ANONYMOUS.alerts.items as unknown as Record<string, unknown>[];
const [rNoData, , rLevel, gNone, gLevel, closeApproach] = items;
const level = (e: Record<string, unknown>) => e['space_weather_level'] as Record<string, unknown>;

describe('noticeAnnouncement', () => {
  it('names a close approach as an alert, never as a newly found encounter', () => {
    expect(say(closeApproach)).toBe(
      'New alert, close approach: OBJECT AJ (57036) and SL-12 DEB (27958), miss distance 2 km at 2026-09-30 03:34:37 UTC.',
    );
  });

  it('names a level entered with its badge and the list title', () => {
    expect(say(gLevel)).toBe(
      'New alert, G4 Severe: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC.',
    );
    expect(say(rLevel)).toBe(
      'New alert, R1 Minor: R1 level from GOES-16 X-ray flux 1.06e-5 W m-2 (class M1.0) at 2024-05-10 03:24 UTC.',
    );
  });

  it('names a change between levels with the level it changed from', () => {
    expect(level(gLevel)['trigger']).toBe('level_change');
    const changed = {
      ...gLevel,
      event_id: 'composed/G/3-to-4',
      space_weather_level: { ...level(gLevel), previous_state: 'level', previous_derived_level: 3 },
    };
    expect(say(changed)).toBe(
      'New alert, G4 Severe, changed from G3: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC.',
    );
  });

  it('names a revision to a different level as revised from the earlier level', () => {
    const revised = {
      ...gLevel,
      event_id: 'composed/G/3-revised-to-4',
      space_weather_level: {
        ...level(gLevel),
        previous_state: 'level',
        previous_derived_level: 3,
        trigger: 'revision',
      },
    };
    expect(say(revised)).toBe(
      'New alert, G4 Severe, revised from G3: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC, a revised Kp value.',
    );
  });

  it('names a revision at the same level with no earlier level', () => {
    const revised = {
      ...gLevel,
      event_id: 'composed/G/4-revised',
      space_weather_level: {
        ...level(gLevel),
        previous_state: 'level',
        previous_derived_level: 4,
        trigger: 'revision',
      },
    };
    expect(say(revised)).toBe(
      'New alert, G4 Severe: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC, a revised Kp value.',
    );
  });

  it('names a level left through its title, which carries the level it left', () => {
    expect(say(gNone)).toBe(
      'New alert. Below G1: SWPC estimated planetary Kp 3.67, 2024-05-12 06:00 to 09:00 UTC, after G3.',
    );
    const toNoData = {
      ...rNoData,
      event_id: 'composed/R/1-to-no-data',
      space_weather_level: {
        ...level(rNoData),
        previous_state: 'level',
        previous_derived_level: 1,
      },
    };
    expect(say(toNoData)).toBe(
      'New alert. No data for GOES-18 X-ray flux since 2026-09-24 08:22 UTC, after R1.',
    );
    expect(say(ALERTS_ENDED.alerts.items[0] as unknown as Record<string, unknown>)).toBe(
      "New alert. GOES-18 X-ray flux is no longer SWPC's primary series; GOES-19 took over. Newest GOES-18 record at 2026-09-29 23:02 UTC, last level R1.",
    );
  });

  it('names the level left in the lead when an ended title cannot carry it', () => {
    const ended = ALERTS_ENDED.alerts.items[0] as unknown as Record<string, unknown>;
    const noTime = {
      ...ended,
      event_id: 'composed/R/ended-no-time',
      space_weather_level: { ...level(ended), no_data_since: 'not a time' },
    };
    expect(say(noTime)).toBe(
      "New alert, last level R1. GOES-18 X-ray flux is no longer SWPC's primary series; GOES-19 took over.",
    );
  });
});

describe('groupAnnouncement', () => {
  const alertOf = (event: Record<string, unknown>) => {
    const { acknowledgement: _, ...alert } = event;
    return noticeAlert(dataOf<AlertStoredData>(ALERT_STORED, { alert_stored: alert }).alert_stored);
  };

  it('says a single alert in its own sentence', () => {
    expect(plain(groupAnnouncement([alertOf(gLevel)]))).toBe(say(gLevel));
  });

  it('counts two or more and reads the top alert of the list, never calling it newest', () => {
    const three = groupAnnouncement([alertOf(closeApproach), alertOf(gLevel), alertOf(rLevel)]);
    expect(plain(three)).toBe(
      '3 new alerts. Top of the list, close approach: OBJECT AJ (57036) and SL-12 DEB (27958), miss distance 2 km at 2026-09-30 03:34:37 UTC.',
    );
    expect(plain(groupAnnouncement([alertOf(gLevel), alertOf(rLevel)]))).toBe(
      '2 new alerts. Top of the list, G4 Severe: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC.',
    );
    expect(plain(groupAnnouncement([alertOf(gNone), alertOf(rLevel)]))).toBe(
      '2 new alerts. Top of the list. Below G1: SWPC estimated planetary Kp 3.67, 2024-05-12 06:00 to 09:00 UTC, after G3.',
    );
    expect(plain(three)).not.toContain('Newest');
  });

  it('uses the opening it is given in every lead', () => {
    const ended = ALERTS_ENDED.alerts.items[0] as unknown as Record<string, unknown>;
    const cases = [
      closeApproach,
      gLevel,
      gNone,
      {
        ...gLevel,
        space_weather_level: {
          ...level(gLevel),
          previous_state: 'level',
          previous_derived_level: 3,
        },
      },
      { ...ended, space_weather_level: { ...level(ended), no_data_since: 'not a time' } },
      {
        ...gLevel,
        space_weather_level: {
          ...level(gLevel),
          previous_state: 'level',
          previous_derived_level: 4,
          trigger: 'revision',
        },
      },
    ];
    for (const c of cases) {
      const top = plain(noticeAnnouncement(alertOf(c), 'Top of the list'));
      expect(top.startsWith('Top of the list')).toBe(true);
      expect(top).toBe(say(c).replace(/^New alert/, 'Top of the list'));
    }
  });
});

describe('pausedAnnouncement', () => {
  it('counts the alerts that arrived while paused', () => {
    expect(plain(pausedAnnouncement(1))).toBe('1 alert arrived while paused.');
    expect(plain(pausedAnnouncement(4))).toBe('4 alerts arrived while paused.');
  });

  it('says or more when the refresh could not count every alert', () => {
    expect(plain(pausedAnnouncement(20, true))).toBe('20 or more alerts arrived while paused.');
    expect(plain(pausedAnnouncement(20, false))).toBe('20 alerts arrived while paused.');
  });

  it('names what the list holds under its filter', () => {
    expect(plain(pausedAnnouncement(1, false, NOUNS.close_approach))).toBe(
      '1 close approach arrived while paused.',
    );
    expect(plain(pausedAnnouncement(2, false, NOUNS.close_approach))).toBe(
      '2 close approaches arrived while paused.',
    );
    expect(plain(pausedAnnouncement(20, true, NOUNS.space_weather_level))).toBe(
      '20 or more space weather alerts arrived while paused.',
    );
    expect(plain(pausedAnnouncement(1, false, NOUNS.space_weather_level))).toBe(
      '1 space weather alert arrived while paused.',
    );
  });
});

describe('held and away announcements', () => {
  const alertOf = (e: Record<string, unknown>) => {
    const { acknowledgement: _, ...alert } = e;
    return noticeAlert(dataOf<AlertStoredData>(ALERT_STORED, { alert_stored: alert }).alert_stored);
  };

  it('says a held alert waits above the list rather than that it is listed (F30)', () => {
    expect(plain(heldAnnouncement([alertOf(closeApproach)]))).toBe(
      'New alert waiting above the list, close approach: OBJECT AJ (57036) and SL-12 DEB (27958), miss distance 2 km at 2026-09-30 03:34:37 UTC.',
    );
    expect(plain(heldAnnouncement([alertOf(gLevel), alertOf(closeApproach)]))).toBe(
      '2 new alerts waiting above the list. The top one, G4 Severe: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC.',
    );
    expect(plain(heldAnnouncement([alertOf(gLevel)]))).not.toMatch(/Top of the list/);
  });

  it('gives a later held entry the total the bar shows, so it matches the bar (F39)', () => {
    expect(plain(heldAnnouncement([alertOf(gLevel), alertOf(closeApproach)], 3))).toBe(
      '2 more new alerts waiting above the list, 3 in all. The top one, G4 Severe: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC.',
    );
    expect(plain(heldAnnouncement([alertOf(closeApproach)], 1))).toBe(
      plain(heldAnnouncement([alertOf(closeApproach)])),
    );
    expect(plain(heldAnnouncement([alertOf(gLevel), alertOf(closeApproach)], 2))).toBe(
      plain(heldAnnouncement([alertOf(gLevel), alertOf(closeApproach)])),
    );
  });

  it('names a single later held alert the new one, in a sentence of its own', () => {
    expect(plain(heldAnnouncement([alertOf(closeApproach)], 3))).toBe(
      '1 more new alert waiting above the list, 3 in all. The new one, close approach: OBJECT AJ (57036) and SL-12 DEB (27958), miss distance 2 km at 2026-09-30 03:34:37 UTC.',
    );
    expect(plain(heldAnnouncement([alertOf(gLevel)], 2))).toBe(
      '1 more new alert waiting above the list, 2 in all. The new one, G4 Severe: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC.',
    );
    // A level left keeps its own sentence, since the title carries the level it left.
    expect(plain(heldAnnouncement([alertOf(gNone)], 3))).toBe(
      '1 more new alert waiting above the list, 3 in all. The new one. Below G1: SWPC estimated planetary Kp 3.67, 2024-05-12 06:00 to 09:00 UTC, after G3.',
    );
  });

  it('counts the alerts that arrived while the console was not shown', () => {
    expect(plain(awayAnnouncement(1))).toBe('1 alert arrived while you were away.');
    expect(plain(awayAnnouncement(3))).toBe('3 alerts arrived while you were away.');
  });

  it('names what the list holds, and says or more when the count can be short', () => {
    expect(plain(awayAnnouncement(1, false, NOUNS.close_approach))).toBe(
      '1 close approach arrived while you were away.',
    );
    expect(plain(awayAnnouncement(2, false, NOUNS.close_approach))).toBe(
      '2 close approaches arrived while you were away.',
    );
    expect(plain(awayAnnouncement(4, false, NOUNS.space_weather_level))).toBe(
      '4 space weather alerts arrived while you were away.',
    );
    expect(plain(awayAnnouncement(3, true))).toBe('3 or more alerts arrived while you were away.');
    expect(plain(awayAnnouncement(20, true, NOUNS.close_approach))).toBe(
      '20 or more close approaches arrived while you were away.',
    );
  });
});
