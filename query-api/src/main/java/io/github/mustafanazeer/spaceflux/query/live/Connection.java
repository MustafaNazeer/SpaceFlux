package io.github.mustafanazeer.spaceflux.query.live;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** One WebSocket connection's slot and limits, kept in its session attributes from the handshake on. */
final class Connection {

    static final String KEY = Connection.class.getName();

    private static final int RESERVED = 0;
    private static final int OPEN = 1;
    private static final int RELEASED = 2;

    private final Connections connections;
    private final String address;
    private final AtomicInteger state = new AtomicInteger(RESERVED);
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicBoolean first = new AtomicBoolean(true);
    private volatile WebSocketSession session;
    private volatile MessageBudget budget;
    private volatile ScheduledFuture<?> lifetime;

    Connection(Connections connections, String address) {
        this.connections = connections;
        this.address = address;
    }

    /** Marks the slot as an open connection; false when it was already given back. */
    boolean opened() {
        return state.compareAndSet(RESERVED, OPEN);
    }

    /** Gives the slot back when the connection never opened; true when it did so. */
    boolean releaseIfNotOpened() {
        if (state.compareAndSet(RESERVED, RELEASED)) {
            connections.release(address);
            return true;
        }
        return false;
    }

    /**
     * Gives the slot back and lets go of the session and the lifetime task, so nothing the connection held stays
     * reachable after it closed; any later call does nothing.
     */
    synchronized void release() {
        ScheduledFuture<?> end = lifetime;
        if (end != null) {
            end.cancel(false);
        }
        session = null;
        budget = null;
        lifetime = null;
        if (state.getAndSet(RELEASED) != RELEASED) {
            connections.release(address);
        }
    }

    /**
     * Gives the open connection its session, budget and lifetime task. When the connection already closed, the task
     * is cancelled at once and false is returned, so a close that races the open leaves nothing scheduled.
     */
    synchronized boolean attach(WebSocketSession session, MessageBudget budget, ScheduledFuture<?> lifetime) {
        if (state.get() != OPEN) {
            lifetime.cancel(false);
            return false;
        }
        this.session = session;
        this.budget = budget;
        this.lifetime = lifetime;
        return true;
    }

    /**
     * Counts a message against the budget. The first message is the client's connection_init, which the budget does
     * not count; anything else sent first is refused by the protocol handler.
     */
    boolean allowMessage(long nanos) {
        if (first.getAndSet(false)) {
            return true;
        }
        MessageBudget b = budget;
        return b == null || b.allow(nanos);
    }

    /** Takes the connection's one subscription; false when it already has one. */
    boolean subscribe() {
        return subscribed.compareAndSet(false, true);
    }

    void unsubscribe() {
        subscribed.set(false);
    }

    /**
     * Closes the connection on a thread of its own: closing waits for a send in progress, which a client that stopped
     * reading holds for up to the send timeout, and the caller must never wait for that.
     */
    void close(CloseStatus status) {
        WebSocketSession s = session;
        if (s == null) {
            return;
        }
        Thread.ofVirtual().name("websocket-close").start(() -> {
            try {
                s.close(status);
            } catch (IOException | RuntimeException e) {
                // Already closed, or closing failed: Tomcat ends the connection either way.
            }
        });
    }
}
