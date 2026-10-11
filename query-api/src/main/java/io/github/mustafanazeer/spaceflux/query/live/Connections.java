package io.github.mustafanazeer.spaceflux.query.live;

import java.util.HashMap;
import java.util.Map;

/**
 * Open WebSocket connections, per client address and across the service (ADR 0014, decision 6). A connection holds
 * its slot from the handshake on, before it is even upgraded, until it is released once.
 */
public final class Connections {

    enum Refusal { ADDRESS, SERVICE }

    /** Either a reserved connection or why none was reserved. */
    record Reservation(Connection connection, Refusal refusal) {
    }

    private final int perAddress;
    private final int total;
    private final Map<String, Integer> byAddress = new HashMap<>();
    private int open;

    Connections(int perAddress, int total) {
        this.perAddress = perAddress;
        this.total = total;
    }

    synchronized Reservation reserve(String address) {
        int fromAddress = byAddress.getOrDefault(address, 0);
        if (fromAddress >= perAddress) {
            return new Reservation(null, Refusal.ADDRESS);
        }
        if (open >= total) {
            return new Reservation(null, Refusal.SERVICE);
        }
        byAddress.put(address, fromAddress + 1);
        open++;
        return new Reservation(new Connection(this, address), null);
    }

    synchronized void release(String address) {
        int left = byAddress.getOrDefault(address, 0) - 1;
        if (left <= 0) {
            byAddress.remove(address);
        } else {
            byAddress.put(address, left);
        }
        open--;
    }

    /** Connections holding a slot across the service. */
    public synchronized int open() {
        return open;
    }

    synchronized int open(String address) {
        return byAddress.getOrDefault(address, 0);
    }

    /** Addresses holding at least one slot. */
    synchronized int addresses() {
        return byAddress.size();
    }
}
