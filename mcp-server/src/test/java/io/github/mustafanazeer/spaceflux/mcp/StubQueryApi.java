package io.github.mustafanazeer.spaceflux.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Stands in for query-api: records every request and answers each with a body naming its path and query. */
final class StubQueryApi implements AutoCloseable {

    /** A request as query-api saw it. */
    record Seen(String method, String pathAndQuery, Map<String, List<String>> headers) {
    }

    /** The catalog number the stub answers with a problem body, and the one it answers with a failure. */
    static final long NOT_FOUND = 404404;
    static final long FAILING = 500500;

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final HttpServer server;
    final String base;

    StubQueryApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static String body(String pathAndQuery) {
        return "{\"answered\":\"" + pathAndQuery.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private void answer(HttpExchange e) throws IOException {
        String pathAndQuery = e.getRequestURI().getRawPath()
                + (e.getRequestURI().getRawQuery() == null ? "" : "?" + e.getRequestURI().getRawQuery());
        seen.add(new Seen(e.getRequestMethod(), pathAndQuery, Map.copyOf(e.getRequestHeaders())));
        int status = 200;
        String type = "application/json";
        String text = body(pathAndQuery);
        if (pathAndQuery.equals("/api/catalog/" + NOT_FOUND)) {
            status = 404;
            type = "application/problem+json";
            text = "{\"title\":\"Not Found\",\"status\":404,\"detail\":\"No catalog object has this number.\","
                    + "\"instance\":\"/api/catalog/" + NOT_FOUND + "\",\"correlation_id\":\"x\"}";
        } else if (pathAndQuery.equals("/api/catalog/" + FAILING)) {
            status = 500;
            type = "application/problem+json";
            text = "{\"detail\":\"The request failed.\"}";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", type);
        e.sendResponseHeaders(status, bytes.length);
        e.getResponseBody().write(bytes);
        e.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
