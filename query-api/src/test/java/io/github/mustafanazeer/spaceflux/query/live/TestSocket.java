package io.github.mustafanazeer.spaceflux.query.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A graphql-transport-ws client on the JDK's own WebSocket, so a test controls every message, header and close.
 * Server pings are answered with a pong unless {@link #answerPings} is off; they are never returned by
 * {@link #next}.
 */
final class TestSocket implements WebSocket.Listener {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PATH = "/api/graphql/ws";
    static final String DASHBOARD = "http://localhost:4200";

    /** A handshake the server refused, with its HTTP status and headers. */
    static final class Refused extends Exception {
        final int status;
        final java.net.http.HttpHeaders headers;
        final String body;

        Refused(WebSocketHandshakeException e) {
            super("refused with " + e.getResponse().statusCode());
            this.status = e.getResponse().statusCode();
            this.headers = e.getResponse().headers();
            this.body = String.valueOf(e.getResponse().body());
        }
    }

    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    volatile String closeReason;
    volatile boolean answerPings = true;
    volatile int pings;
    private final StringBuilder partial = new StringBuilder();
    WebSocket socket;

    static TestSocket open(String base, String origin) throws Refused {
        return open(base, origin, Map.of());
    }

    static TestSocket open(String base, String origin, Map<String, String> headers) throws Refused {
        TestSocket listener = new TestSocket();
        WebSocket.Builder b = HTTP.newWebSocketBuilder().subprotocols("graphql-transport-ws")
                .connectTimeout(Duration.ofSeconds(10));
        if (origin != null) {
            b.header("Origin", origin);
        }
        headers.forEach(b::header);
        try {
            listener.socket = b.buildAsync(URI.create(base.replace("http://", "ws://") + PATH), listener).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException h) {
                throw new Refused(h);
            }
            throw e;
        }
        return listener;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            if (text.contains("\"type\":\"ping\"")) {
                pings++;
                if (answerPings) {
                    webSocket.sendText("{\"type\":\"pong\"}", true);
                }
            } else {
                messages.add(text);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closeReason = reason;
        closeCode.complete(statusCode);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closeCode.complete(-1);
    }

    void send(String text) {
        socket.sendText(text, true).join();
    }

    void sendBinary(byte[] bytes) {
        socket.sendBinary(ByteBuffer.wrap(bytes), true).join();
    }

    TestSocket init() throws Exception {
        send("{\"type\":\"connection_init\"}");
        JsonNode ack = next();
        if (!"connection_ack".equals(ack.get("type").asString())) {
            throw new AssertionError("expected connection_ack, got " + ack);
        }
        return this;
    }

    void subscribe(String id, String query) throws Exception {
        send(JSON.writeValueAsString(Map.of("id", id, "type", "subscribe", "payload", Map.of("query", query))));
    }

    JsonNode next() throws Exception {
        return next(Duration.ofSeconds(10));
    }

    JsonNode next(Duration wait) throws Exception {
        String text = messages.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (text == null) {
            throw new AssertionError("no message within " + wait
                    + (closeCode.isDone() ? ", closed with " + closeCode.join() + " " + closeReason : ""));
        }
        return JSON.readTree(text);
    }

    /** The raw text of the next message, for checks on exactly what was written. */
    String nextText() throws Exception {
        String text = messages.poll(10, TimeUnit.SECONDS);
        if (text == null) {
            throw new AssertionError("no message");
        }
        return text;
    }

    /** Nothing arrives within the wait. */
    boolean quiet(Duration wait) throws Exception {
        String text = messages.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (text != null) {
            messages.add(text);
            return false;
        }
        return true;
    }

    int closed(Duration wait) throws Exception {
        return closeCode.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    void close() {
        if (!closeCode.isDone()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(e -> null);
        }
        try {
            closeCode.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            socket.abort();
        }
    }
}
