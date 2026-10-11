import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';

import { AckState } from '../ack/ack-state';
import { Badge } from '../badge/badge';
import { Segments } from '../badge/segments';
import { AlertList } from './alert-list';
import { alertView } from './alert-view';
import { LogEntry } from './announcer';

/** Recent alerts, newest first, with their acknowledgement state. Read only. */
@Component({
  selector: 'app-recent-alerts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AckState, Badge, Segments],
  templateUrl: './recent-alerts.html',
  styleUrl: './recent-alerts.css',
  host: { style: 'display: contents' },
})
export class RecentAlerts {
  readonly page = input.required<AlertList | undefined>();
  /** The live log's entries, oldest first; each is spoken once when it is added. */
  readonly log = input<LogEntry[]>([]);
  /** The id of the newest log entry drawn, so the data layer trims only entries already spoken. */
  readonly logRendered = output<number>();
  readonly now = input.required<number>();
  /** Browser time of the newest failed fetch, so a first fetch that fails does not read as loading forever. */
  readonly failedAt = input<number | undefined>(undefined);
  /** While updates are paused no refresh is sent, so a failed load waits for the viewer to resume. */
  readonly paused = input(false);

  /** Text of the persistent status region: loading, failed, or empty once the list is shown. */
  protected readonly status = computed(() => {
    if (this.page()) {
      return '';
    }
    return this.failedAt() === undefined
      ? 'Loading recent alerts.'
      : `Recent alerts could not be loaded. The page tries again ${this.paused() ? 'when updates resume' : 'at the next refresh'}.`;
  });

  constructor() {
    afterRenderEffect(() => {
      const newest = this.log().at(-1);
      if (newest) {
        this.logRendered.emit(newest.id);
      }
    });
  }

  protected readonly views = computed(() => {
    const p = this.page();
    const now = this.now();
    return p ? p.items.map((a) => ({ alert: a, view: alertView(a, now) })) : [];
  });
}
