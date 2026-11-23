package de.atstck.kitly.cal.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** A small JSON client for tests: real HTTP against the running application. */
public final class Http {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public record Result(int status, Map<String, Object> body, String raw) {

        @SuppressWarnings("unchecked")
        public <T> T data() {
            return (T) body.get("data");
        }

        @SuppressWarnings("unchecked")
        public String errorMessage() {
            return String.valueOf(((Map<String, Object>) body.get("error")).get("message"));
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;
    private final String defaultKey;

    public Http(int port, String defaultKey) {
        this.base = "http://127.0.0.1:" + port;
        this.defaultKey = defaultKey;
    }

    public Result get(String path) {
        return send("GET", path, null, defaultKey);
    }

    public Result get(String path, String key) {
        return send("GET", path, null, key);
    }

    public Result post(String path, Object body) {
        return send("POST", path, body, defaultKey);
    }

    public Result post(String path, Object body, String key) {
        return send("POST", path, body, key);
    }

    /** @param key sent as Bearer token; null sends no credentials */
    public Result send(String method, String path, Object body, String key) {
        return send(method, path, body == null ? null : body instanceof String text ? text : MAPPER.writeValueAsString(body),
                key == null ? Map.of() : Map.of("Authorization", "Bearer " + key));
    }

    @SuppressWarnings("unchecked")
    public Result send(String method, String path, String rawBody, Map<String, String> headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                    .method(method, rawBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(rawBody));
            if (rawBody != null) {
                request.header("Content-Type", "application/json");
            }
            headers.forEach(request::header);
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = response.body() == null || response.body().isBlank()
                    ? Map.of() : MAPPER.readValue(response.body(), Map.class);
            return new Result(response.statusCode(), parsed, response.body());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
