import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { Segment } from '../format';

/** Renders a sentence built from segments: plain text, <time> elements and units with superscripts. */
@Component({
  selector: 'app-segments',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `@for (s of segments(); track $index) {
    @switch (s.kind) {
      @case ('time') {<time [attr.datetime]="s.datetime">{{ s.label }}</time>}
      @case ('unit') {@if (s.unit === 'W m-2') {W m<sup>&minus;2</sup>} @else {{{ s.unit }}}}
      @default {{{ s.text }}}
    }
  }`,
})
export class Segments {
  readonly segments = input.required<Segment[]>();
}
