import { Provider } from '@angular/core';

import { WEB_SOCKET_IMPL } from '../app/data/alert-socket';

export interface Sent {
  type: string;
  id?: string;
  payload?: { query?: string; operationName?: string };
}

/**
 * A browser WebSocket stand in that the test plays the server for. The graphql-ws client under test is the real
 * one, so what it sends and how it reacts to each server move is the library's own behavior.
 */
export class FakeSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  /** Every socket the client opened, oldest first. */
  static all: FakeSocket[] = [];
  /** When set, a close the client asks for waits in CLOSING until the test calls finishClose, as a slow network does. */
  static deferClose = false;

  readyState = FakeSocket.CONNECTING;
  readonly raw: string[] = [];
  closedByClient: { code?: number; reason?: string } | undefined;
  /** Every close the client asked for, in order. */
  readonly closeCalls: { code?: number; reason?: string }[] = [];

  onopen: ((e: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
  onclose: ((e: unknown) => void) | null = null;

  constructor(
    readonly url: string,
    readonly protocol: string,
  ) {
    FakeSocket.all.push(this);
  }

  static reset(): void {
    FakeSocket.all = [];
    FakeSocket.deferClose = false;
  }

  static latest(): FakeSocket {
    return FakeSocket.all[FakeSocket.all.length - 1];
  }

  get sent(): Sent[] {
    return this.raw.map((m) => JSON.parse(m) as Sent);
  }

  send(data: string): void {
    this.raw.push(data);
  }

  close(code?: number, reason?: string): void {
    if (this.readyState === FakeSocket.CLOSED) {
      return;
    }
    this.closeCalls.push({ code, reason });
    if (this.readyState === FakeSocket.CLOSING) {
      return;
    }
    this.closedByClient = { code, reason };
    if (FakeSocket.deferClose) {
      this.readyState = FakeSocket.CLOSING;
      return;
    }
    this.finishClose();
  }

  /** Completes a close the client asked for while deferClose was set. */
  finishClose(): void {
    const { code, reason } = this.closedByClient ?? {};
    this.readyState = FakeSocket.CLOSED;
    this.onclose?.({ code: code ?? 1005, reason: reason ?? '', wasClean: true });
  }

  // The server's side.

  open(): void {
    this.readyState = FakeSocket.OPEN;
    this.onopen?.({});
  }

  receive(message: object): void {
    this.onmessage?.({ data: JSON.stringify(message) });
  }

  /** Opens, answers connection_init with connection_ack. */
  accept(): void {
    this.open();
    this.receive({ type: 'connection_ack' });
  }

  subscribeId(): string {
    const sub = this.sent.find((m) => m.type === 'subscribe');
    if (!sub?.id) {
      throw new Error('no subscribe message was sent');
    }
    return sub.id;
  }

  next(data: object): void {
    this.receive({ id: this.subscribeId(), type: 'next', payload: { data } });
  }

  /** A close from the server after the upgrade, such as 1001 at the end of the connection's lifetime. */
  serverClose(code: number, reason = ''): void {
    this.readyState = FakeSocket.CLOSED;
    this.onclose?.({ code, reason, wasClean: true });
  }

  /**
   * A handshake the server refused before the upgrade (403, 429 or 503). A browser exposes none of the status:
   * it fires error, then close with 1006.
   */
  refuse(): void {
    this.readyState = FakeSocket.CLOSED;
    this.onerror?.(new Event('error'));
    this.onclose?.({ code: 1006, reason: '', wasClean: false });
  }
}

export function provideFakeSocket(): Provider {
  return { provide: WEB_SOCKET_IMPL, useValue: FakeSocket as unknown as typeof WebSocket };
}
