package io.github.mustafanazeer.spaceflux.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A fixed window of one clock minute per tool (ADR 0015, decision 6): each tool takes at most the configured number
 * of calls in a minute, and the next is refused until the minute ends. Counted in this process only, which is the
 * one replica Compose runs.
 */
@Component
class CallLimiter {

    private record Window(long minute, int calls) {
    }

    private final Clock clock;
    private final int perMinute;
    private final Map<String, Window> windows = new HashMap<>();

    CallLimiter(Clock clock, @Value("${spaceflux.mcp.calls-per-minute}") int perMinute) {
        if (perMinute < 1) {
            throw new IllegalStateException("spaceflux.mcp.calls-per-minute is 1 or more.");
        }
        this.clock = clock;
        this.perMinute = perMinute;
    }

    int perMinute() {
        return perMinute;
    }

    /** Counts the call and returns null, or returns when the window ends if the tool has used its calls. */
    synchronized Instant refusedUntil(String tool) {
        long minute = Math.floorDiv(clock.millis(), 60_000L);
        Window w = windows.get(tool);
        if (w == null || w.minute() != minute) {
            w = new Window(minute, 0);
        }
        if (w.calls() >= perMinute) {
            return Instant.ofEpochMilli((minute + 1) * 60_000L);
        }
        windows.put(tool, new Window(minute, w.calls() + 1));
        return null;
    }
}
