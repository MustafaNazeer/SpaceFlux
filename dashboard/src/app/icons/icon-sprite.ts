import { ChangeDetectionStrategy, Component } from '@angular/core';

/** The icon shapes from docs/design/dashboard.html, referenced with <use href="#i-...">. */
@Component({
  selector: 'app-icon-sprite',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `
    <svg width="0" height="0" style="position: absolute" aria-hidden="true" focusable="false">
      <symbol id="i-none" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" stroke-width="1.5" /></symbol>
      <symbol id="i-l1" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" stroke-width="1.5" /><rect x="5" y="7.25" width="6" height="1.5" fill="currentColor" /></symbol>
      <symbol id="i-l2" viewBox="0 0 16 16"><path d="M8 1.75 14.75 14H1.25Z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" /><rect x="5.5" y="9.5" width="5" height="1.5" fill="currentColor" /></symbol>
      <symbol id="i-l3" viewBox="0 0 16 16"><path d="M8 1.75 14.75 14H1.25Z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" /><rect x="7.25" y="6" width="1.5" height="4.5" fill="currentColor" /><rect x="7.25" y="11.25" width="1.5" height="1.5" fill="currentColor" /></symbol>
      <symbol id="i-l4" viewBox="0 0 16 16"><path d="M5.2 1.5h5.6l3.7 3.7v5.6l-3.7 3.7H5.2l-3.7-3.7V5.2Z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" /><rect x="7.25" y="4.25" width="1.5" height="5" fill="currentColor" /><rect x="7.25" y="10.25" width="1.5" height="1.5" fill="currentColor" /></symbol>
      <symbol id="i-l5" viewBox="0 0 16 16"><path fill-rule="evenodd" fill="currentColor" d="M5 1h6l4 4v6l-4 4H5l-4-4V5Zm.75 3.25v5h1.5v-5Zm3 0v5h1.5v-5Zm-3 6v1.5h1.5v-1.5Zm3 0v1.5h1.5v-1.5Z" /></symbol>
      <symbol id="i-nodata" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="2.5 2" /><path d="M3.8 12.2 12.2 3.8" stroke="currentColor" stroke-width="1.5" /></symbol>
      <symbol id="i-clock" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.25" fill="none" stroke="currentColor" stroke-width="1.5" /><path d="M8 4.5V8l2.5 1.75" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" /></symbol>
      <symbol id="i-approach" viewBox="0 0 16 16"><circle cx="6" cy="8" r="4.25" fill="none" stroke="currentColor" stroke-width="1.5" /><circle cx="10" cy="8" r="4.25" fill="none" stroke="currentColor" stroke-width="1.5" /></symbol>
      <symbol id="i-check" viewBox="0 0 16 16"><path d="M3 8.5 6.5 12 13 4.5" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" /></symbol>
      <symbol id="i-open" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" stroke-width="1.5" /></symbol>
    </svg>
  `,
})
export class IconSprite {}
