import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApollo } from 'apollo-angular';

import { FakeSocket, provideFakeSocket } from '../../testing/fake-socket';
import { dataOf } from '../../testing/graphql';
import { ALERTS_ANONYMOUS } from '../../testing/fixtures';
import {
  ALERT_SOCKET_PATH,
  Reconnect,
  RECONNECT,
  reconnectDelay,
  SERVER_SILENCE_MS,
} from './alert-socket';
import { apolloOptions } from './graphql';
import { LiveAlerts } from './live-alerts';
import { ALERT_STORED, AlertNotice, AlertStoredData } from './queries';

// The server's connection rules (docs/api/graphql.md, Live alert subscription).
const SERVER_PING_MS = 30_000;
const SERVER_READ_IDLE_MS = 75_000;
const SERVER_INIT_MS = 5_000;
const ACK_WAIT_MS = 10_000;

it('waits as long as the server does before calling a connection silent', () => {
  expect(SERVER_SILENCE_MS).toBe(SERVER_READ_IDLE_MS);
});
const MAX_TEXT_MESSAGE = 8_192;
const MAX_MESSAGES_PER_MINUTE = 20;

const POLICY: Reconnect = { firstMs: 1_000, maxMs: 60_000, stableMs: 60_000, random: () => 1 };

/** A notice as the server would send it for the newest close approach in the recorded list. */
function notice(index = 5): AlertStoredData {
  const { acknowledgement: _, ...alert } = ALERTS_ANONYMOUS.alerts.items[index] as Record<
    string,
    unknown
  >;
  return dataOf<AlertStoredData>(ALERT_STORED, { alert_stored: alert });
}

describe('LiveAlerts', () => {
  let received: AlertNotice[];
  let live: LiveAlerts;

  function setUp(policy: Reconnect = POLICY): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApollo(apolloOptions),
        provideFakeSocket(),
        { provide: RECONNECT, useValue: policy },
      ],
    });
    live = TestBed.inject(LiveAlerts);
  }

  async function startConnected(): Promise<FakeSocket> {
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    const socket = FakeSocket.latest();
    socket.accept();
    await vi.advanceTimersByTimeAsync(0);
    return socket;
  }

  beforeEach(() => {
    vi.useFakeTimers();
    FakeSocket.reset();
    received = [];
  });

  afterEach(() => {
    live?.stop();
    vi.useRealTimers();
  });

  it('opens nothing until started', async () => {
    setUp();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(FakeSocket.all.length).toBe(0);
  });

  it('opens the socket path on the page host with graphql-transport-ws, then sends init and one subscribe', async () => {
    setUp();
    const socket = await startConnected();
    expect(FakeSocket.all.length).toBe(1);
    expect(socket.url).toBe(`ws://${location.host}${ALERT_SOCKET_PATH}`);
    expect(socket.protocol).toBe('graphql-transport-ws');
    const sent = socket.sent;
    expect(sent.map((m) => m.type)).toEqual(['connection_init', 'subscribe']);
    expect(sent[1].payload?.operationName).toBe('AlertStored');
    expect(sent[1].payload?.query).toContain('alert_stored');
  });

  it('sends a subscribe message within the server text message limit', async () => {
    setUp();
    const socket = await startConnected();
    const subscribe = socket.raw.find(
      (m) => (JSON.parse(m) as { type: string }).type === 'subscribe',
    )!;
    expect(subscribe.length).toBeLessThanOrEqual(MAX_TEXT_MESSAGE);
  });

  it('hands each notice to the listener', async () => {
    setUp();
    const socket = await startConnected();
    socket.next(notice(5));
    socket.next(notice(4));
    await vi.advanceTimersByTimeAsync(0);
    expect(received.map((n) => n.event_id)).toEqual([
      ALERTS_ANONYMOUS.alerts.items[5].event_id,
      ALERTS_ANONYMOUS.alerts.items[4].event_id,
    ]);
    expect(received[0]).toEqual(notice(5).alert_stored);
  });

  it('answers every server ping with a pong, sends no ping of its own, and stays under the message rate', async () => {
    setUp();
    const socket = await startConnected();
    const before = socket.raw.length;
    const minutes = 10;
    for (let t = 0; t < (minutes * 60_000) / SERVER_PING_MS; t++) {
      await vi.advanceTimersByTimeAsync(SERVER_PING_MS);
      socket.receive({ type: 'ping' });
    }
    const after = socket.sent.slice(before);
    // One pong per ping and nothing else: the client is never silent for the server's read idle limit, and sends
    // two messages a minute against the server's twenty.
    expect(after.length).toBe((minutes * 60_000) / SERVER_PING_MS);
    expect(after.every((m) => m.type === 'pong')).toBe(true);
    expect(SERVER_PING_MS).toBeLessThan(SERVER_READ_IDLE_MS);
    expect(after.length / minutes).toBeLessThan(MAX_MESSAGES_PER_MINUTE);
    expect(FakeSocket.all.length).toBe(1);
  });

  it('reconnects after the server ends the connection with 1001, after the first backoff delay and not before', async () => {
    setUp();
    const first = await startConnected();
    for (let t = 0; t < (60 * 60_000) / SERVER_PING_MS; t++) {
      await vi.advanceTimersByTimeAsync(SERVER_PING_MS);
      first.receive({ type: 'ping' });
    }
    first.serverClose(1001, 'Lifetime');
    await vi.advanceTimersByTimeAsync(POLICY.firstMs - 1);
    expect(FakeSocket.all.length).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeSocket.all.length).toBe(2);
    const second = FakeSocket.latest();
    second.accept();
    await vi.advanceTimersByTimeAsync(0);
    expect(second.sent.map((m) => m.type)).toEqual(['connection_init', 'subscribe']);
    second.next(notice());
    await vi.advanceTimersByTimeAsync(0);
    expect(received.length).toBe(1);
  });

  it('backs off after refused handshakes, doubling up to the cap, never in a tight loop', async () => {
    setUp();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    const opened: number[] = [Date.now()];
    FakeSocket.latest().refuse();
    const end = Date.now() + 10 * 60_000;
    let seen = 1;
    while (Date.now() < end) {
      await vi.advanceTimersByTimeAsync(100);
      if (FakeSocket.all.length > seen) {
        seen = FakeSocket.all.length;
        opened.push(Date.now());
        FakeSocket.latest().refuse();
      }
    }
    const gaps = opened.slice(1).map((t, i) => t - opened[i]);
    expect(gaps.slice(0, 8)).toEqual([1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000]);
    expect(Math.min(...gaps)).toBeGreaterThanOrEqual(POLICY.firstMs);
    expect(FakeSocket.all.length).toBeLessThanOrEqual(16);
  });

  it('keeps growing the delay when each connection is acknowledged and then closed at once', async () => {
    setUp();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    const opened: number[] = [];
    for (let i = 0; i < 5; i++) {
      opened.push(Date.now());
      const s = FakeSocket.latest();
      s.accept();
      await vi.advanceTimersByTimeAsync(0);
      s.serverClose(1008, 'Too many messages');
      const count = FakeSocket.all.length;
      while (FakeSocket.all.length === count) {
        await vi.advanceTimersByTimeAsync(100);
      }
    }
    const gaps = opened.slice(1).map((t, i) => t - opened[i]);
    expect(gaps).toEqual([1_000, 2_000, 4_000, 8_000]);
  });

  it('does not reconnect at once when the server closes normally while subscribed', async () => {
    setUp();
    const socket = await startConnected();
    socket.serverClose(1000, 'Normal Closure');
    await vi.advanceTimersByTimeAsync(POLICY.firstMs - 1);
    expect(FakeSocket.all.length).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeSocket.all.length).toBe(2);
  });

  it('resets the backoff after a connection that stayed up', async () => {
    setUp();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    FakeSocket.latest().refuse();
    await vi.advanceTimersByTimeAsync(1_000);
    FakeSocket.latest().refuse();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(FakeSocket.all.length).toBe(3);
    const up = FakeSocket.latest();
    up.accept();
    await vi.advanceTimersByTimeAsync(POLICY.stableMs);
    up.serverClose(1001);
    await vi.advanceTimersByTimeAsync(POLICY.firstMs);
    expect(FakeSocket.all.length).toBe(4);
  });

  it('closes an acknowledged connection the server has been silent on for 75 s, then reconnects through the backoff', async () => {
    setUp();
    const socket = await startConnected();
    await vi.advanceTimersByTimeAsync(SERVER_READ_IDLE_MS - 1);
    expect(socket.closedByClient).toBeUndefined();
    await vi.advanceTimersByTimeAsync(1);
    expect(socket.closedByClient?.code).toBe(4499);
    expect(FakeSocket.all.length).toBe(1);
    await vi.advanceTimersByTimeAsync(POLICY.firstMs - 1);
    expect(FakeSocket.all.length).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeSocket.all.length).toBe(2);
  });

  it('counts any server message, a notice or a ping, as a sign of life', async () => {
    setUp();
    const socket = await startConnected();
    await vi.advanceTimersByTimeAsync(60_000);
    socket.next(notice());
    await vi.advanceTimersByTimeAsync(60_000);
    socket.receive({ type: 'ping' });
    await vi.advanceTimersByTimeAsync(SERVER_READ_IDLE_MS - 1);
    expect(socket.closedByClient).toBeUndefined();
    await vi.advanceTimersByTimeAsync(1);
    expect(socket.closedByClient?.code).toBe(4499);
  });

  it('counts silence from the acknowledgement, and stops watching a connection closed by stop', async () => {
    setUp();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    FakeSocket.latest().open();
    FakeSocket.latest().receive({ type: 'ping' });
    await vi.advanceTimersByTimeAsync(ACK_WAIT_MS - 1_000);
    FakeSocket.latest().receive({ type: 'connection_ack' });
    // Silence is counted from the acknowledgement, not from the ping before it.
    await vi.advanceTimersByTimeAsync(SERVER_READ_IDLE_MS - 1);
    expect(FakeSocket.latest().closedByClient).toBeUndefined();
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeSocket.latest().closedByClient?.code).toBe(4499);
    await vi.advanceTimersByTimeAsync(POLICY.firstMs);
    expect(FakeSocket.all.length).toBe(2);
    FakeSocket.latest().accept();
    await vi.advanceTimersByTimeAsync(1_000);
    live.stop();
    await vi.advanceTimersByTimeAsync(SERVER_READ_IDLE_MS * 4);
    expect(FakeSocket.latest().closedByClient?.code).toBe(1000);
    expect(FakeSocket.all.length).toBe(2);
  });

  it('closes the socket on stop, with complete then 1000, and never reconnects while stopped', async () => {
    setUp();
    const socket = await startConnected();
    live.stop();
    await vi.advanceTimersByTimeAsync(0);
    expect(socket.sent.at(-1)?.type).toBe('complete');
    expect(socket.closedByClient?.code).toBe(1000);
    await vi.advanceTimersByTimeAsync(10 * 60_000);
    expect(FakeSocket.all.length).toBe(1);
  });

  it('cancels a pending reconnect on stop, and opens at once on the next start', async () => {
    setUp();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    FakeSocket.latest().refuse();
    await vi.advanceTimersByTimeAsync(0);
    live.stop();
    await vi.advanceTimersByTimeAsync(10 * 60_000);
    expect(FakeSocket.all.length).toBe(1);
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    expect(FakeSocket.all.length).toBe(2);
  });

  it('starts the backoff from the first delay again after stop and start', async () => {
    setUp();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    for (const wait of [1_000, 2_000, 4_000]) {
      FakeSocket.latest().refuse();
      await vi.advanceTimersByTimeAsync(wait);
    }
    expect(FakeSocket.all.length).toBe(4);
    live.stop();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    // graphql-ws reuses a connection that is still opening rather than opening a second one.
    expect(FakeSocket.all.length).toBe(4);
    FakeSocket.latest().refuse();
    await vi.advanceTimersByTimeAsync(POLICY.firstMs - 1);
    expect(FakeSocket.all.length).toBe(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeSocket.all.length).toBe(5);
  });

  it('reconnects cleanly when resumed while the previous close is still in progress', async () => {
    setUp();
    FakeSocket.deferClose = true;
    const first = await startConnected();
    live.stop();
    await vi.advanceTimersByTimeAsync(0);
    expect(first.readyState).toBe(FakeSocket.CLOSING);
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    expect(FakeSocket.all.length).toBe(1);
    first.finishClose();
    await vi.advanceTimersByTimeAsync(POLICY.firstMs - 1);
    expect(FakeSocket.all.length).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeSocket.all.length).toBe(2);
    const second = FakeSocket.latest();
    second.accept();
    await vi.advanceTimersByTimeAsync(0);
    expect(second.sent.map((m) => m.type)).toEqual(['connection_init', 'subscribe']);
    second.next(notice());
    await vi.advanceTimersByTimeAsync(0);
    expect(received.length).toBe(1);
  });

  it('leaves no timer running after stop, even while the close is still in progress', async () => {
    setUp();
    FakeSocket.deferClose = true;
    const first = await startConnected();
    await vi.advanceTimersByTimeAsync(30_000);
    live.stop();
    await vi.advanceTimersByTimeAsync(0);
    expect(first.readyState).toBe(FakeSocket.CLOSING);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('restarts the silence watch on a stop and start that reuse the open connection', async () => {
    setUp();
    const first = await startConnected();
    await vi.advanceTimersByTimeAsync(SERVER_READ_IDLE_MS - 5_000);
    live.stop();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    expect(FakeSocket.all.length).toBe(1);
    expect(first.closeCalls).toEqual([]);
    await vi.advanceTimersByTimeAsync(SERVER_READ_IDLE_MS - 1);
    expect(first.closeCalls).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(first.closeCalls.map((c) => c.code)).toEqual([4499]);
  });

  it('gives up on a connection that is never acknowledged after 10 s, and reconnects through the backoff', async () => {
    setUp();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    const socket = FakeSocket.latest();
    socket.open();
    await vi.advanceTimersByTimeAsync(ACK_WAIT_MS - 1);
    expect(socket.closedByClient).toBeUndefined();
    await vi.advanceTimersByTimeAsync(1);
    expect(socket.closedByClient?.code).toBe(4504);
    await vi.advanceTimersByTimeAsync(POLICY.firstMs);
    expect(FakeSocket.all.length).toBe(2);
  });

  it('waits for the acknowledgement longer than the server waits for connection_init', () => {
    expect(ACK_WAIT_MS).toBeGreaterThan(SERVER_INIT_MS);
  });

  it('opens nothing and delivers nothing once destroyed with a reconnect waiting', async () => {
    setUp();
    live.start((n) => received.push(n));
    await vi.advanceTimersByTimeAsync(0);
    FakeSocket.latest().refuse();
    await vi.advanceTimersByTimeAsync(0);
    TestBed.resetTestingModule();
    await vi.advanceTimersByTimeAsync(10 * 60_000);
    expect(FakeSocket.all.length).toBe(1);
    expect(received).toEqual([]);
  });

  it('delivers nothing after stop, even a notice already on the wire', async () => {
    setUp();
    const socket = await startConnected();
    const id = socket.subscribeId();
    live.stop();
    socket.receive({ id, type: 'next', payload: { data: notice() } });
    await vi.advanceTimersByTimeAsync(0);
    expect(received).toEqual([]);
  });
});

describe('reconnectDelay', () => {
  it('doubles from the first delay up to the cap, with jitter between half and all of it', () => {
    const at = (attempt: number, r: number) =>
      reconnectDelay(attempt, { ...POLICY, random: () => r });
    expect([0, 1, 2, 3, 4, 5, 6, 7, 20, 1_000].map((a) => at(a, 1))).toEqual([
      1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000, 60_000,
    ]);
    expect([0, 1, 6, 1_000].map((a) => at(a, 0))).toEqual([500, 1_000, 30_000, 30_000]);
  });
});
