import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { Acknowledgement } from '../data/queries';
import { Clock } from '../data/clock';
import { dateHm, dateOf, datetimeAttr, hm, parseUtc } from '../format';

/**
 * The acknowledgement state of an alert. Anonymous viewers get the action and its time only; the principal and
 * note are shown when the API returned them, which it does for the signed in operator alone.
 */
@Component({
  selector: 'app-ack-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `
    <span class="ack-state"
      ><svg class="icon" aria-hidden="true"><use [attr.href]="view().icon" /></svg>{{ view().label }}
      @if (view().datetime) {<time [attr.datetime]="view().datetime">{{ view().time }}</time>}
      @if (ack()?.principal) {{{ ' by ' + ack()?.principal }}}</span
    >
    @if (showNote() && ack()?.note) {
      <p class="ack-note">{{ ack()?.note }}</p>
    }
  `,
  styles: `
    .ack-note {
      color: var(--text-secondary);
      border-left: 2px solid var(--border-subtle);
      padding-left: var(--space-2);
    }
  `,
})
export class AckState {
  readonly ack = input.required<Acknowledgement | null>();
  readonly showNote = input(false);
  private readonly now = inject(Clock).now;

  protected readonly view = computed(() => {
    const a = this.ack();
    if (!a) {
      return { icon: '#i-open', label: 'Not acknowledged', datetime: null, time: '' };
    }
    const ms = parseUtc(a.acted_at);
    const acknowledged = a.action === 'acknowledge';
    return {
      icon: acknowledged ? '#i-check' : '#i-open',
      label: acknowledged ? 'Acknowledged' : 'Unacknowledged',
      datetime: Number.isNaN(ms) ? null : datetimeAttr(ms),
      time: Number.isNaN(ms)
        ? ''
        : `${dateOf(ms) === dateOf(this.now()) ? hm(ms) : dateHm(ms)} UTC`,
    };
  });
}
