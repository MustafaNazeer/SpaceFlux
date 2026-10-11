import { DOCUMENT } from '@angular/common';
import { inject, InjectionToken } from '@angular/core';
import { Client, createClient } from 'graphql-ws';

/** The live alert subscription's own path, served only over WebSocket (docs/api/graphql.md). */
export const ALERT_SOCKET_PATH = '/api/graphql/ws';

/** The WebSocket class the client opens connections with; tests replace it. */
export const WEB_SOCKET_IMPL = new InjectionToken<typeof WebSocket>('WEB_SOCKET_IMPL', {
  factory: () => WebSocket,
});

/**
 * How long an acknowledged connection may go without any message from the server before the client gives up on it.
 * The server pings every 30 s and closes a client silent for 75 s, so this mirrors its own limit.
 */
export const SERVER_SILENCE_MS = 75_000;

export interface Reconnect {
  /** Delay before the first reconnect; each further attempt doubles it. */
  firstMs: number;
  /** The most any one delay can grow to. */
  maxMs: number;
  /** A connection that stayed up this long starts the next backoff from the first delay again. */
  stableMs: number;
  /** A number in [0, 1) that spreads each delay between half and all of its value. */
  random: () => number;
}

export const RECONNECT = new InjectionToken<Reconnect>('RECONNECT', {
  factory: () => ({ firstMs: 1_000, maxMs: 60_000, stableMs: 60_000, random: Math.random }),
});

/** The wait before reconnect attempt number `attempt` (0 for the first). */
export function reconnectDelay(attempt: number, policy: Reconnect): number {
  const doublings = Math.min(attempt, 30);
  const full = Math.min(policy.maxMs, policy.firstMs * 2 ** doublings);
  return Math.round(full * (0.5 + 0.5 * policy.random()));
}

/**
 * The graphql-transport-ws client. It opens a connection only while a subscription is active and answers each
 * server ping with a pong by itself. It never retries on its own (retryAttempts 0): a browser reports a refused
 * handshake as an error event, which the library's default would not retry, and a normal close would be retried
 * at once, so LiveAlerts owns every reconnect and its backoff instead.
 */
export const ALERT_SOCKET = new InjectionToken<Client>('ALERT_SOCKET', {
  factory: () => {
    const doc = inject(DOCUMENT);
    return createClient({
      url: () => {
        const url = new URL(ALERT_SOCKET_PATH, doc.baseURI);
        url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
        return url.toString();
      },
      webSocketImpl: inject(WEB_SOCKET_IMPL),
      lazy: true,
      retryAttempts: 0,
      // The server closes a connection without connection_init after 5 s; an attempt still unacknowledged after
      // twice that has hung, and its close goes through the usual backoff.
      connectionAckWaitTimeout: 10_000,
    });
  },
});
