package de.atstck.kitly.cal.web;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.service.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import de.atstck.kitly.cal.config.JsonWriter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every request by API key and decides which world (live or sandbox) it acts in.
 *
 * <p>Accepts the key the way the app sends it today: {@code Authorization: Bearer <key>}
 * (lib/cal-booking.ts), a bare {@code Authorization: <key>} and {@code x-cal-secret-key}
 * (app/api/cal/slots/route.ts). Every credential that is present must be valid and point to the
 * same world.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String CONTEXT_ATTRIBUTE = "kitlyCal.requestContext";
    private static final int MAX_BODY_BYTES = 32 * 1024;

    private final CalConfig config;
    private final Clock clock;
    private final JsonWriter json;

    public ApiKeyFilter(CalConfig config, Clock clock, JsonWriter json) {
        this.config = config;
        this.clock = clock;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health") || path.startsWith("/_mock/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<RequestContext> context = authenticate(
                request.getHeader("x-cal-secret-key"), request.getHeader("Authorization"), config);
        if (context.isEmpty()) {
            writeError(request, response, 401, "UNAUTHORIZED", "Invalid API Key");
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            writeError(request, response, 413, "PAYLOAD_TOO_LARGE", "Request body is too large.");
            return;
        }
        request.setAttribute(CONTEXT_ATTRIBUTE, context.get());
        chain.doFilter(request, response);
    }

    public static Optional<RequestContext> authenticate(String secretKeyHeader, String authorizationHeader, CalConfig config) {
        List<String> candidates = new ArrayList<>();
        if (secretKeyHeader != null && !secretKeyHeader.isBlank()) {
            candidates.add(secretKeyHeader.trim());
        }
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            String value = authorizationHeader.trim();
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                value = value.substring(7).trim();
            }
            if (!value.isEmpty()) {
                candidates.add(value);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Set<Boolean> worlds = new HashSet<>();
        for (String candidate : candidates) {
            boolean live = matchesAny(candidate, config.liveKeys());
            boolean sandbox = matchesAny(candidate, config.sandboxKeys());
            if (!live && !sandbox) {
                return Optional.empty();
            }
            worlds.add(sandbox);
        }
        return worlds.size() == 1 ? Optional.of(new RequestContext(worlds.iterator().next())) : Optional.empty();
    }

    /** Constant-time membership test; hashing first makes all inputs the same length. */
    private static boolean matchesAny(String candidate, List<String> keys) {
        byte[] candidateDigest = sha256(candidate);
        boolean match = false;
        for (String key : keys) {
            if (MessageDigest.isEqual(candidateDigest, sha256(key))) {
                match = true;
            }
        }
        return match;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(json.write(ApiError.body(code, message, request.getRequestURI(), clock.instant())));
    }
}
