package de.atstck.kitly.cal.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;

final class Responses {

    private Responses() {
    }

    static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("data", data);
        return body;
    }

    static ResponseEntity<Map<String, Object>> success(Object data, int status) {
        return send(envelope(data), status);
    }

    static ResponseEntity<Map<String, Object>> send(Map<String, Object> body, int status) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
