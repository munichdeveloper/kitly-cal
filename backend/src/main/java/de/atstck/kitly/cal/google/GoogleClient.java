package de.atstck.kitly.cal.google;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal Google API client: exchanges the refresh token for access tokens and sends JSON requests.
 * Plain {@link HttpClient} instead of the Google SDK keeps the dependency list as short as Kitly's.
 */
public class GoogleClient {

    /** Answer of the Google API that is not a success. */
    public static class GoogleApiException extends Exception {

        private final int status;

        public GoogleApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }

        /** 4xx except rate limits and expired tokens: retrying the same request will not help. */
        public boolean permanent() {
            return status >= 400 && status < 500 && status != 401 && status != 408 && status != 429;
        }
    }

    private final GoogleProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http;
    private final Duration timeout;
    private String accessToken;
    private Instant accessTokenExpiry = Instant.EPOCH;

    public GoogleClient(GoogleProperties properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.timeout = Duration.ofMillis(properties.timeout());
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Sends a JSON request to the Calendar API. {@code path} starts with "/" and is already encoded. */
    public Map<String, Object> send(String method, String path, Object body) throws Exception {
        String json = body == null ? null : mapper.writeValueAsString(body);
        HttpResponse<String> response = call(method, path, json, accessToken());
        if (response.statusCode() == 401) {
            // Token revoked or expired early: fetch a new one once.
            synchronized (this) {
                accessTokenExpiry = Instant.EPOCH;
            }
            response = call(method, path, json, accessToken());
        }
        return parse(response, "Google Calendar " + method + " " + path.replaceAll("\\?.*", ""));
    }

    private HttpResponse<String> call(String method, String path, String json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.api() + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (json == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    synchronized String accessToken() throws Exception {
        if (accessToken != null && clock.instant().isBefore(accessTokenExpiry)) {
            return accessToken;
        }
        String form = "grant_type=refresh_token"
                + "&client_id=" + encode(properties.clientId())
                + "&client_secret=" + encode(properties.clientSecret())
                + "&refresh_token=" + encode(properties.refreshToken());
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.token()))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        Map<String, Object> answer = parse(http.send(request, HttpResponse.BodyHandlers.ofString()), "Google token refresh");
        Object token = answer.get("access_token");
        if (!(token instanceof String text) || text.isBlank()) {
            throw new GoogleApiException(502, "Google token refresh returned no access_token.");
        }
        long expiresIn = answer.get("expires_in") instanceof Number number ? number.longValue() : 3600;
        accessToken = text;
        // Renew a minute early so a token never expires mid-request.
        accessTokenExpiry = clock.instant().plusSeconds(Math.max(0, expiresIn - 60));
        return accessToken;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(HttpResponse<String> response, String what) throws Exception {
        String body = response.body() == null ? "" : response.body();
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // Error bodies of Google never contain the token; still cut them short for the outbox log.
            throw new GoogleApiException(status, what + " answered " + status + ": "
                    + body.substring(0, Math.min(300, body.length())));
        }
        return body.isBlank() ? Map.of() : mapper.readValue(body, Map.class);
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value.trim(), StandardCharsets.UTF_8);
    }
}
