package de.atstck.kitly.cal.web;

import de.atstck.kitly.cal.domain.Iso;
import de.atstck.kitly.cal.domain.OutboxEvent;
import de.atstck.kitly.cal.domain.OutboxStatus;
import de.atstck.kitly.cal.domain.ServiceException;
import de.atstck.kitly.cal.outbox.OutboxDispatcher;
import de.atstck.kitly.cal.outbox.Wire;
import de.atstck.kitly.cal.service.RequestContext;
import de.atstck.kitly.cal.store.BookingStore;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import de.atstck.kitly.cal.config.JsonWriter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operations. Not part of the Cal API; for cron jobs, monitoring and deploy checks. Needs an API key. */
@RestController
public class InternalController {

    private final BookingStore store;
    private final OutboxDispatcher dispatcher;
    private final Clock clock;
    private final JsonWriter json;

    public InternalController(BookingStore store, OutboxDispatcher dispatcher, Clock clock, JsonWriter json) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.json = json;
    }

    /** Tells a caller which world its key acts in, so a test script can refuse to run with a live key. */
    @GetMapping("/internal/whoami")
    ResponseEntity<Map<String, Object>> whoami(@RequestAttribute(ApiKeyFilter.CONTEXT_ATTRIBUTE) RequestContext context) {
        return Responses.success(Map.of("sandbox", context.sandbox()), 200);
    }

    @PostMapping("/internal/outbox/run")
    ResponseEntity<Map<String, Object>> runOutbox() {
        OutboxDispatcher.Summary summary = dispatcher.runOnce(20);
        return Responses.success(
                Map.of("delivered", summary.delivered(), "retrying", summary.retrying(), "dead", summary.dead()), 200);
    }

    /** Delivery state without payloads: those hold customer data. */
    @GetMapping("/internal/outbox")
    ResponseEntity<Map<String, Object>> outbox(
            @RequestParam(required = false) String status,
            @RequestAttribute(ApiKeyFilter.CONTEXT_ATTRIBUTE) RequestContext context) {
        OutboxStatus filter = null;
        if (status != null) {
            try {
                filter = OutboxStatus.fromWire(status);
            } catch (IllegalArgumentException e) {
                throw ServiceException.badRequest("status must be one of: pending, delivered, dead.");
            }
        }
        List<Map<String, Object>> events = store.listOutbox(filter, 100).stream()
                .filter(event -> event.sandbox() == context.sandbox())
                .map(InternalController::overview)
                .toList();
        return Responses.success(events, 200);
    }

    /** Sends a signed PING to the app. Checks URL, secret and that no redirect is in between. */
    @PostMapping("/internal/webhook/ping")
    ResponseEntity<Map<String, Object>> ping(@RequestAttribute(ApiKeyFilter.CONTEXT_ATTRIBUTE) RequestContext context) {
        try {
            dispatcher.ping(context.sandbox(), json.write(Wire.ping(clock.instant())));
            return Responses.success(Map.of("delivered", true), 200);
        } catch (OutboxDispatcher.PermanentDeliveryException e) {
            throw ServiceException.badRequest(e.getMessage());
        } catch (Exception e) {
            throw new ServiceException(502, "WEBHOOK_FAILED", e.getMessage() == null ? "Webhook failed." : e.getMessage());
        }
    }

    private static Map<String, Object> overview(OutboxEvent event) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("id", event.id());
        overview.put("bookingUid", event.bookingUid());
        overview.put("kind", event.kind().wire());
        overview.put("status", event.status().wire());
        overview.put("attempts", event.attempts());
        overview.put("nextAttemptAt", Iso.utc(event.nextAttemptAt()));
        overview.put("lastError", event.lastError());
        overview.put("createdAt", Iso.utc(event.createdAt()));
        overview.put("deliveredAt", Iso.utc(event.deliveredAt()));
        return overview;
    }
}
