import { DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';

export const CLOCK_TICK_MS = new InjectionToken<number>('CLOCK_TICK_MS', { factory: () => 30_000 });

/** The browser's clock as a signal, for ages the API does not compute itself. */
@Injectable({ providedIn: 'root' })
export class Clock {
  private readonly ms = signal(Date.now());

  readonly now = this.ms.asReadonly();

  constructor() {
    const tick = inject(CLOCK_TICK_MS);
    if (tick > 0) {
      const id = setInterval(() => this.ms.set(Date.now()), tick);
      inject(DestroyRef).onDestroy(() => clearInterval(id));
    }
  }

  set(ms: number): void {
    this.ms.set(ms);
  }
}
