import { computed, inject, Injectable } from '@angular/core';

import { SessionService } from '../session/session.service';

/**
 * Whether principals and notes may be drawn: only while the operator is signed in. Hidden from the moment a sign out
 * succeeds or the session is found ended, before any data is fetched again, so no name or note stays on screen.
 */
@Injectable({ providedIn: 'root' })
export class OperatorFields {
  private readonly session = inject(SessionService);

  readonly hidden = computed(() => this.session.state().kind !== 'signed_in');
}
