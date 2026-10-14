import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AckState } from '../ack/ack-state';
import { Badge } from '../badge/badge';
import { Segments } from '../badge/segments';
import { Alert } from '../data/queries';
import { alertView } from './alert-view';

/**
 * The left border color class of a New item: its level's alert color, or border-strong for anything with no level
 * color of its own (a close approach, a level that was left, no data, ended, a screening run).
 */
export function newBorder(alert: Alert): string {
  const l = alert.space_weather_level;
  if (
    l &&
    l.state === 'level' &&
    l.derived_level !== null &&
    l.derived_level >= 1 &&
    l.derived_level <= 5
  ) {
    return `l${l.derived_level}`;
  }
  return alert.close_approach ? 'approach' : 'plain';
}

/** The inside of one alert list item: badges, receive time, the title linking to the alert's page, its state. */
@Component({
  selector: 'app-alert-item',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AckState, Badge, Segments, RouterLink],
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `<div class="alert-top">
      <span class="alert-badges">@if (view().badge; as b) {<app-badge [badge]="b" />}@if (isNew()) {<span class="badge new"><svg class="icon" aria-hidden="true"><use href="#i-new" /></svg>New</span>}</span>
      <span class="caption"><app-segments [segments]="view().received" /></span>
    </div>
    <h3 class="alert-title"><a class="title-link" [routerLink]="['/alerts', alert().event_id]"><app-segments [segments]="view().title" /></a></h3>
    @if (view().acknowledgeable) {
      <app-ack-state [ack]="alert().acknowledgement" [showNote]="true" />
    }`,
})
export class AlertItem {
  readonly alert = input.required<Alert>();
  readonly now = input.required<number>();
  readonly isNew = input(false);

  protected readonly view = computed(() => alertView(this.alert(), this.now()));
}
