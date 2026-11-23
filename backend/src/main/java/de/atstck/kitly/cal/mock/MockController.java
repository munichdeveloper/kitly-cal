package de.atstck.kitly.cal.mock;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.domain.Iso;
import de.atstck.kitly.cal.domain.OutboxEvent;
import de.atstck.kitly.cal.outbox.Wire;
import de.atstck.kitly.cal.store.BookingStore;
import de.atstck.kitly.cal.store.InMemoryBookingStore;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test controls of the mock profile: inspect what happened, move the clock, simulate a foreign
 * calendar entry, start over. Needs no API key and exists only in this profile.
 */
@RestController
@Profile("mock")
public class MockController {

    private final InMemoryBookingStore store;
    private final CalConfig config;
    private final MutableClock clock;
    private final RecordingWebhookSender webhooks;
    private final RecordingNotifier notifier;
    private final RecordingCalendar calendar;
    private final StaticBusyTimes busyTimes;
    private final MockConfig.SequentialUids uids;

    public MockController(InMemoryBookingStore store, CalConfig config, MutableClock clock, RecordingWebhookSender webhooks,
            RecordingNotifier notifier, RecordingCalendar calendar, StaticBusyTimes busyTimes, MockConfig.SequentialUids uids) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.webhooks = webhooks;
        this.notifier = notifier;
        this.calendar = calendar;
        this.busyTimes = busyTimes;
        this.uids = uids;
    }

    @GetMapping("/_mock/state")
    Map<String, Object> state() {
        List<Map<String, Object>> bookings = new ArrayList<>();
        for (boolean sandbox : new boolean[] {false, true}) {
            store.listBookings(new BookingStore.BookingFilter(sandbox, null, null, null, 1000, 0))
                    .forEach(booking -> bookings.add(Wire.booking(booking, config)));
        }
        List<Map<String, Object>> outbox = new ArrayList<>();
        for (OutboxEvent event : store.listOutbox(null, 1000)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", event.id());
            entry.put("bookingUid", event.bookingUid());
            entry.put("sandbox", event.sandbox());
            entry.put("kind", event.kind().wire());
            entry.put("status", event.status().wire());
            entry.put("attempts", event.attempts());
            entry.put("lastError", event.lastError());
            outbox.add(entry);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("now", Iso.utc(clock.instant()));
        state.put("bookings", bookings);
        state.put("outbox", outbox);
        state.put("webhooks", webhooks.sent().stream()
                .map(request -> Map.of("url", request.url(), "headers", request.headers(), "body", request.body()))
                .toList());
        state.put("confirmationMails", notifier.confirmations().stream()
                .map(item -> Map.of("to", item.context().booking().attendee().email(), "uid", item.context().booking().uid()))
                .toList());
        state.put("calendarEntries", calendar.events().stream()
                .map(item -> Map.of("uid", item.booking().uid(), "start", Iso.utc(item.booking().start())))
                .toList());
        return state;
    }

    @PostMapping("/_mock/reset")
    Map<String, Object> reset() {
        store.reset();
        webhooks.reset();
        notifier.reset();
        calendar.reset();
        busyTimes.reset();
        clock.reset();
        uids.reset();
        return Map.of("ok", true);
    }

    /** Body: {"advanceMinutes": 200} or {"now": "2026-10-05T07:00:00Z"} (optionally with "freeze": true). */
    @PostMapping("/_mock/clock")
    ResponseEntity<Map<String, Object>> clock(@RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body != null && body.get("now") instanceof String now) {
                if (Boolean.TRUE.equals(body.get("freeze"))) {
                    clock.setFixed(OffsetDateTime.parse(now).toInstant());
                } else {
                    clock.jumpTo(OffsetDateTime.parse(now).toInstant());
                }
            }
            if (body != null && body.get("advanceMinutes") instanceof Number minutes) {
                clock.advance(Duration.ofSeconds(Math.round(minutes.doubleValue() * 60)));
            }
        } catch (DateTimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "now must be an ISO 8601 date-time."));
        }
        return ResponseEntity.ok(Map.of("now", Iso.utc(clock.instant())));
    }

    /** Body: {"start": "...", "end": "..."}: an appointment in the host's own calendar. */
    @PostMapping("/_mock/busy")
    ResponseEntity<Map<String, Object>> busy(@RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null || !(body.get("start") instanceof String start) || !(body.get("end") instanceof String end)) {
                return ResponseEntity.badRequest().body(Map.of("error", "start and end (ISO 8601) are required."));
            }
            busyTimes.block(OffsetDateTime.parse(start).toInstant(), OffsetDateTime.parse(end).toInstant());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (DateTimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "start and end must be ISO 8601 date-times."));
        }
    }
}
