import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AckChange, AckControls } from '../ack/ack-controls';
import { AckState } from '../ack/ack-state';
import { KeptNotes } from '../ack/kept-notes';
import { Badge } from '../badge/badge';
import { Segments } from '../badge/segments';
import { Acknowledgement, Alert } from '../data/queries';
import { Segment } from '../format';
import { SessionService } from '../session/session.service';
import { alertView } from './alert-view';

type TimeSegment = Extract<Segment, { kind: 'time' }>;

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

/** A sentence's text as the page shows it, a unit's superscript included. */
export function segmentsText(segments: Segment[]): string {
  return segments
    .map((s) =>
      s.kind === 'text'
        ? s.text
        : s.kind === 'time'
          ? s.label
          : s.unit === 'W m-2'
            ? 'W m\u22122'
            : s.unit,
    )
    .join('');
}

/**
 * The inside of one alert list item: badges, receive time, the title linking to the alert's page, its state, and for
 * the signed in operator, where `actions` is set, the acknowledge controls.
 */
@Component({
  selector: 'app-alert-item',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AckControls, AckState, Badge, Segments, RouterLink],
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
      @if (actions() && (signedIn() || kept())) {
        <app-ack-controls [eventId]="alert().event_id" [ack]="alert().acknowledgement" [title]="titleText()" [received]="receivedText()" (changed)="changed($event)" />
      }
    }`,
})
export class AlertItem {
  readonly alert = input.required<Alert>();
  readonly now = input.required<number>();
  readonly isNew = input(false);
  /** Offers the acknowledge controls to the signed in operator; the object page's list stays read only. */
  readonly actions = input(false);
  readonly ackChange = output<{ eventId: string; ack: Acknowledgement | null }>();
  protected readonly signedIn = inject(SessionService).signedIn;
  private readonly keptNotes = inject(KeptNotes);
  /** A note kept when the session ended stays in place, read only, while signed out. */
  protected readonly kept = computed(() => this.keptNotes.get(this.alert().event_id) !== undefined);
  /** The title link's text as shown, which names the item's note form. */
  protected readonly titleText = computed(() => segmentsText(this.view().title));
  /** The receive time as the item shows it, which with the title names the note form. */
  protected readonly receivedText = computed(() => {
    const time = this.view().received.find((s): s is TimeSegment => s.kind === 'time');
    return time?.label ?? '';
  });

  protected changed(change: AckChange): void {
    this.ackChange.emit({ eventId: this.alert().event_id, ack: change.ack });
  }

  protected readonly view = computed(() => alertView(this.alert(), this.now()));
}
