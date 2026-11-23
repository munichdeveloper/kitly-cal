package de.atstck.kitly.cal.web;

import de.atstck.kitly.cal.store.BookingStore;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness of the service and its store. Needs no API key. */
@RestController
public class HealthController {

    private final BookingStore store;

    public HealthController(BookingStore store) {
        this.store = store;
    }

    @GetMapping("/health")
    ResponseEntity<Map<String, Object>> health() {
        try {
            store.ping();
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "error",
                    "error", Map.of("code", "STORE_UNAVAILABLE", "message", "Store is not reachable.")));
        }
    }
}
