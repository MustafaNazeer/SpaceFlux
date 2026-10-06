package io.github.mustafanazeer.spaceflux.query;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keeps cookies the way a browser on the API's origin would, by hand, since the JDK's cookie manager will not send a
 * Secure cookie over plain HTTP. Sends the XSRF-TOKEN cookie back as the X-XSRF-TOKEN header on a POST, as Angular's
 * HttpClient does.
 */
public final class Browser {

    public static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String base;
    private final HttpClient http;
    public final Map<String, String> cookies = new LinkedHashMap<>();
    public boolean sendXsrfHeader = true;

    public Browser(String base) {
        this(base, HTTP);
    }

    public Browser(String base, HttpClient http) {
        this.base = base;
        this.http = http;
    }

    public HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public HttpResponse<String> postForm(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public HttpResponse<String> postJson(String path, String json) throws Exception {
        return post(path, "application/json", json);
    }

    public HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public HttpResponse<String> send(String method, String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json)));
    }

    public HttpResponse<String> login(String username, String password) throws Exception {
        if (!cookies.containsKey("XSRF-TOKEN")) {
            get("/api/auth/session");
        }
        return postForm("/api/auth/login", Map.of("username", username, "password", password));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        if (!cookies.isEmpty()) {
            b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        HttpRequest probe = b.copy().build();
        if (sendXsrfHeader && !"GET".equals(probe.method()) && cookies.containsKey("XSRF-TOKEN")) {
            b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String c : setCookies(r)) {
            String pair = c.split(";", 2)[0];
            String name = pair.substring(0, pair.indexOf('='));
            String value = pair.substring(pair.indexOf('=') + 1);
            boolean expired = c.toLowerCase().contains("max-age=0") || value.isEmpty();
            if (expired) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
        return r;
    }

    public static List<String> setCookies(HttpResponse<?> r) {
        return r.headers().allValues("Set-Cookie");
    }

    /** The Set-Cookie header that sets this cookie, or null. */
    public static String setCookie(HttpResponse<?> r, String name) {
        return setCookies(r).stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
