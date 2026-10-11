import { DestroyRef, inject, Injectable } from '@angular/core';
import { Apollo } from 'apollo-angular';
import { Subscription } from 'rxjs';

import { ALERT_SOCKET, RECONNECT, reconnectDelay, SERVER_SILENCE_MS } from './alert-socket';
import { ALERT_STORED, AlertNotice } from './queries';

/**
 * The alert_stored subscription, held open while started. Whatever ends it (the server's lifetime close, a refused
 * handshake, a lost network, an error) leads to one reconnect after a backoff delay that doubles up to a cap, and
 * starts again from the first delay only after a connection stayed up. The minute poll covers every gap.
 */
@Injectable({ providedIn: 'root' })
export class LiveAlerts {
  private readonly apollo = inject(Apollo);
  private readonly socket = inject(ALERT_SOCKET);
  private readonly policy = inject(RECONNECT);

  private listener: ((notice: AlertNotice) => void) | null = null;
  private current: Subscription | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private attempt = 0;
  private connectedAt: number | undefined;
  private watchdog: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    const off = [
      this.socket.on('connected', () => {
        this.connectedAt = Date.now();
        this.watch();
      }),
      // Any server message is a sign of life. Before the acknowledgement it is harmless: the acknowledgement restarts
      // the watch, and an attempt with none is closed by the 10 s acknowledgement timeout first.
      this.socket.on('message', () => this.watch()),
      this.socket.on('closed', () => this.closed()),
    ];
    inject(DestroyRef).onDestroy(() => {
      this.stop();
      off.forEach((unlisten) => unlisten());
    });
  }

  /** Subscribes at once, handing each notice to the listener until stop. */
  start(listener: (notice: AlertNotice) => void): void {
    this.stop();
    this.listener = listener;
    this.attempt = 0;
    this.open();
    // A start right after stop can reuse a connection that is still acknowledged; watch it from now.
    if (this.connectedAt !== undefined) {
      this.watch();
    }
  }

  /** Ends the subscription, which closes the connection, and cancels any reconnect waiting to run. */
  stop(): void {
    this.listener = null;
    clearTimeout(this.timer);
    this.timer = undefined;
    clearTimeout(this.watchdog);
    this.watchdog = undefined;
    const sub = this.current;
    this.current = null;
    sub?.unsubscribe();
  }

  private open(): void {
    this.timer = undefined;
    let sub: Subscription | null = null;
    const ended = () => {
      if (sub !== null && this.current === sub) {
        this.current = null;
        this.retry();
      }
    };
    sub = this.apollo.subscribe({ query: ALERT_STORED, fetchPolicy: 'no-cache' }).subscribe({
      next: (result) => {
        const notice = result.data?.alert_stored;
        if (notice) {
          this.listener?.(notice);
        }
      },
      error: ended,
      complete: ended,
    });
    if (this.listener && !sub.closed) {
      this.current = sub;
    } else if (this.listener) {
      this.retry();
    }
  }

  /**
   * Restarts the silence timer. A connection that hears nothing from the server for SERVER_SILENCE_MS (a half open
   * socket after sleep or a network change) is terminated, which reaches closed() and so the usual backoff.
   */
  private watch(): void {
    clearTimeout(this.watchdog);
    this.watchdog = setTimeout(() => {
      this.watchdog = undefined;
      if (this.listener && this.current) {
        this.socket.terminate();
      }
    }, SERVER_SILENCE_MS);
  }

  /** Any close of the connection while subscribed ends this subscription and waits to reconnect. */
  private closed(): void {
    clearTimeout(this.watchdog);
    this.watchdog = undefined;
    if (this.connectedAt !== undefined && Date.now() - this.connectedAt >= this.policy.stableMs) {
      this.attempt = 0;
    }
    this.connectedAt = undefined;
    const sub = this.current;
    if (sub && this.listener) {
      this.current = null;
      sub.unsubscribe();
      this.retry();
    }
  }

  private retry(): void {
    if (!this.listener || this.timer !== undefined) {
      return;
    }
    const delay = reconnectDelay(this.attempt, this.policy);
    this.attempt++;
    this.timer = setTimeout(() => this.open(), delay);
  }
}
