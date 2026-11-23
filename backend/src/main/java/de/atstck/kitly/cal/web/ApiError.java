package de.atstck.kitly.cal.web;

import de.atstck.kitly.cal.domain.Iso;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Error body in the shape the app reads: {@code error.message}. */
final class ApiError {

    private ApiError() {
    }

    static Map<String, Object> body(String code, String message, String path, Instant now) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("timestamp", Iso.utc(now));
        body.put("path", path);
        body.put("error", error);
        return body;
    }
}
