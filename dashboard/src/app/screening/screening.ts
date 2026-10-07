import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { AckState } from '../ack/ack-state';
import { Badge } from '../badge/badge';
import { ScreeningCurrentData, ScreenedObject, SuppressedPair } from '../data/queries';
import {
  count,
  dateHms,
  datetimeAttr,
  days,
  distance,
  duration,
  kmPerS,
  kmPlain,
  parseUtc,
  separation,
} from '../format';
import { staleBadge } from '../scales';

/** A run is current for 24 hours after its window start, or until its window ends (docs/api/rest.md, section 3). */
const CURRENT_FOR_MS = 24 * 60 * 60 * 1000;

/** Why a pair was not screened, in the wording docs/risk/orbital-conventions.md supports. Unknown codes show detail. */
export function suppressionReason(
  p: SuppressedPair,
  reportDistanceM: number,
  reportDistance: string,
): string {
  switch (p.mechanism) {
    case 'static_stack':
      return p.stack_name
        ? `Listed in the same station stack, ${p.stack_name}`
        : 'Listed in the same station stack';
    case 'co_orbiting': {
      const label =
        'Moved together over the whole window (inferred from GP data, not known to be attached)';
      return p.min_separation_m <= reportDistanceM
        ? `${label}. Sampled minimum within ${reportDistance}; approaches for this pair were not computed.`
        : label;
    }
    case 'same_elements':
      return 'Identical element sets';
    default:
      return p.detail;
  }
}

/** The station stack list entry column: only static_stack pairs come from the list. */
export function listEntry(p: SuppressedPair): string {
  if (p.mechanism !== 'static_stack') {
    return 'No list entry';
  }
  return p.stack_entry_may_be_stale ? 'May be stale' : 'Not flagged';
}

interface TimeView {
  datetime: string;
  label: string;
}

function time(iso: string): TimeView {
  const ms = parseUtc(iso);
  return Number.isNaN(ms)
    ? { datetime: '', label: iso }
    : { datetime: datetimeAttr(ms), label: dateHms(ms) };
}

/** The latest screening run from screening_current: its coverage, approaches and suppressed pairs. */
@Component({
  selector: 'app-screening',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AckState, Badge],
  templateUrl: './screening.html',
  styleUrl: './screening.css',
})
export class Screening {
  /** undefined while loading, null when no complete run is stored. */
  readonly current = input.required<ScreeningCurrentData['screening_current'] | undefined>();
  readonly now = input.required<number>();
  readonly failedAt = input<number | undefined>(undefined);
  /** While updates are paused no refresh is sent, so a failed load waits for the viewer to resume. */
  readonly paused = input(false);

  protected readonly status = computed(() => {
    if (this.current() !== undefined) {
      return '';
    }
    return this.failedAt() === undefined
      ? 'Loading the latest screening run.'
      : `The latest screening run could not be loaded. The page tries again ${this.paused() ? 'when updates resume' : 'at the next refresh'}.`;
  });

  protected readonly count = count;
  protected readonly distance = distance;
  protected readonly separation = separation;
  protected readonly suppressionReason = suppressionReason;
  protected readonly listEntry = listEntry;
  protected readonly kmPerS = kmPerS;
  protected readonly days = days;
  protected readonly time = time;

  protected readonly view = computed(() => {
    const c = this.current();
    if (!c) {
      return null;
    }
    const run = c.summary;
    const startMs = parseUtc(run.window_start);
    const endMs = parseUtc(run.window_end);
    const currentUntil = Math.min(startMs + CURRENT_FOR_MS, endMs);
    const suppressedCount = run.suppressed.length + (run.omitted?.suppressed ?? 0);
    const outside = run.rejected.length + run.not_screened.length;
    const outsideCount = outside + (run.omitted?.rejected ?? 0) + (run.omitted?.not_screened ?? 0);
    return {
      run,
      stale: c.stale,
      staleBadge: c.stale ? staleBadge(duration(this.now() - startMs)) : null,
      approaches: c.approaches,
      currentUntil: { datetime: datetimeAttr(currentUntil), label: dateHms(currentUntil) },
      reportDistance: kmPlain(run.report_distance_m),
      suppressedCount,
      suppressedNotListed: run.omitted?.suppressed ?? 0,
      outsideCount,
      outsideNotListed: outsideCount - outside,
      outsideRows: [
        ...run.rejected.map((r) => ({
          number: r.catalog_number,
          name: r.name,
          role: r.role,
          reason: r.reason,
        })),
        ...run.not_screened.map((r) => ({
          number: r.catalog_number,
          name: r.name,
          role: r.role,
          reason: r.reason,
        })),
      ],
    };
  });

  protected objectName(o: ScreenedObject): string {
    return o.name ?? `Object ${o.catalog_number}`;
  }
}
