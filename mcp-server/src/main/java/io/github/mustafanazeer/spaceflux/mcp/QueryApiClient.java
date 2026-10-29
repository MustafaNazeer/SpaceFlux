package io.github.mustafanazeer.spaceflux.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The tool server's only way to query-api: a GET of a fixed path under the configured base, with no credential, no
 * cookie and no redirect followed (ADR 0015, decision 5). It has no method for any other request.
 */
@Component
public class QueryApiClient {

    static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** The status and body query-api answered with. */
    public record Answer(int status, String body) {
    }

    private final HttpClient http;
    private final String base;

    QueryApiClient(@Value("${spaceflux.mcp.query-api-url}") String base) {
        URI uri = URI.create(base);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("spaceflux.mcp.query-api-url is an http or https address with no query.");
        }
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY).connectTimeout(TIMEOUT).build();
    }

    /** {@code path} is one of the tools' fixed paths; every query value is encoded here. */
    public Answer get(String path, Map<String, String> query) throws IOException, InterruptedException {
        StringJoiner q = new StringJoiner("&", "?", "").setEmptyValue("");
        query.forEach((k, v) -> q.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path + q)).GET()
                .header("Accept", "application/json").timeout(TIMEOUT).build();
        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Answer(r.statusCode(), r.body());
    }
}
