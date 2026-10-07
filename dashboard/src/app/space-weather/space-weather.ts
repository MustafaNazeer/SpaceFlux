import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { Badge } from '../badge/badge';
import { Segments } from '../badge/segments';
import { SpaceWeatherCurrentData } from '../data/queries';
import { scaleView } from './scale-view';

const ORDER = ['G', 'R', 'S'];

/** The three scale panels from space_weather_current. */
@Component({
  selector: 'app-space-weather',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Badge, Segments],
  templateUrl: './space-weather.html',
  styleUrl: './space-weather.css',
})
export class SpaceWeather {
  readonly current = input.required<SpaceWeatherCurrentData['space_weather_current'] | undefined>();
  readonly failedAt = input<number | undefined>(undefined);
  /** While updates are paused no refresh is sent, so a failed load waits for the viewer to resume. */
  readonly paused = input(false);

  protected readonly status = computed(() => {
    if (this.current()) {
      return '';
    }
    return this.failedAt() === undefined
      ? 'Loading the current levels.'
      : `The current levels could not be loaded. The page tries again ${this.paused() ? 'when updates resume' : 'at the next refresh'}.`;
  });

  protected readonly views = computed(() => {
    const c = this.current();
    if (!c) {
      return [];
    }
    return [...c.scales]
      .sort((a, b) => ORDER.indexOf(a.scale) - ORDER.indexOf(b.scale))
      .map((s) => scaleView(s, c.as_of));
  });
}
