/** NOAA's level names, from docs/design/tokens.md (the SWPC scales explanation). */
export const LEVEL_NAMES: Record<number, string> = {
  1: 'Minor',
  2: 'Moderate',
  3: 'Strong',
  4: 'Severe',
  5: 'Extreme',
};

export interface BadgeView {
  classes: string;
  icon: string;
  text: string;
}

export function levelBadge(scale: string, level: number, form: 'solid' | 'outline'): BadgeView {
  const name = LEVEL_NAMES[level] ?? '';
  return {
    classes: `${form} l${level}`,
    icon: `i-l${level}`,
    text: `${scale}${level} ${name}`.trim(),
  };
}

export const NONE_BADGE: BadgeView = { classes: 'outline none', icon: 'i-none', text: 'none' };
export const NO_DATA_BADGE: BadgeView = { classes: 'nodata', icon: 'i-nodata', text: 'No data' };
/** An R or S series that another satellite replaced: the no data treatment with its own word. */
export const ENDED_BADGE: BadgeView = { classes: 'nodata', icon: 'i-nodata', text: 'Ended' };
export const APPROACH_BADGE: BadgeView = {
  classes: 'approach',
  icon: 'i-approach',
  text: 'Close approach',
};

export function staleBadge(age: string): BadgeView {
  return { classes: 'stale', icon: 'i-clock', text: `Stale, ${age}` };
}

/** The badge for a state; a level without a level number is shown as no data rather than guessed. */
export function stateBadge(
  scale: string,
  state: string,
  level: number | null,
  form: 'solid' | 'outline',
): BadgeView {
  if (state === 'level' && level !== null && level >= 1 && level <= 5) {
    return levelBadge(scale, level, form);
  }
  return state === 'none' ? NONE_BADGE : NO_DATA_BADGE;
}

/** The measurement each scale is derived from (docs/risk/space-weather-scales.md, Section 4 wording). */
export function measurementName(scale: string, satellite: number | null): string {
  switch (scale) {
    case 'G':
      return 'SWPC estimated planetary Kp';
    case 'R':
      return `${satellite === null ? 'GOES' : `GOES-${satellite}`} X-ray flux`;
    case 'S':
      return `${satellite === null ? 'GOES' : `GOES-${satellite}`} ≥10 MeV proton flux`;
    default:
      return `${scale} scale measurement`;
  }
}

/** Kp covers [T, T + 3 h) (docs/risk/space-weather-scales.md, Section 1.3). */
export const KP_INTERVAL_MS = 3 * 60 * 60 * 1000;

export const NO_DATA_REASONS: Record<string, string> = {
  age_limit: 'Age limit passed',
  rejected: 'No usable value in the newest record',
  zero_run_edge: 'Next to a gap in measurements',
  no_series: 'No series stored yet',
};
