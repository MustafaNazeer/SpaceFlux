import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Segments } from '../badge/segments';
import { DetailFact } from './alert-detail-view';

/** The fields of one alert or catalog entry as a description list; a field that is not set has no row. */
@Component({
  selector: 'app-detail-facts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Segments],
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `<dl class="detail-facts">
    @for (f of facts(); track f.term) {
      <dt>{{ f.term }}</dt>
      <dd>@if (f.link; as l) {<a [routerLink]="l.path">{{ l.label }}</a>} @else {<span [class.mono]="f.mono" [class.event-id]="f.mono" [class.num]="f.num"><app-segments [segments]="f.value" /></span>}@for (s of f.subs; track $index) {<span class="sub" [class.mono]="s.mono" [class.event-id]="s.mono"><app-segments [segments]="s.segments" /></span>}</dd>
    }
  </dl>`,
})
export class DetailFacts {
  readonly facts = input.required<DetailFact[]>();
}
