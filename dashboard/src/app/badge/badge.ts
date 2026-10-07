import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { BadgeView } from '../scales';

/** A badge with its icon shape and text, so a state never depends on color alone. */
@Component({
  selector: 'app-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `<span class="badge {{ badge().classes }}"
    ><svg class="icon" aria-hidden="true"><use [attr.href]="'#' + badge().icon" /></svg>{{ badge().text }}</span
  >`,
})
export class Badge {
  readonly badge = input.required<BadgeView>();
}
