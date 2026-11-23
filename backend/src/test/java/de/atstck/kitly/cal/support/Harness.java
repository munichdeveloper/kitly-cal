package de.atstck.kitly.cal.support;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.config.JsonWriter;
import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.mock.MutableClock;
import de.atstck.kitly.cal.mock.RecordingCalendar;
import de.atstck.kitly.cal.mock.RecordingNotifier;
import de.atstck.kitly.cal.mock.RecordingWebhookSender;
import de.atstck.kitly.cal.mock.StaticBusyTimes;
import de.atstck.kitly.cal.outbox.OutboxDispatcher;
import de.atstck.kitly.cal.service.BookingService;
import de.atstck.kitly.cal.service.RequestContext;
import de.atstck.kitly.cal.store.InMemoryBookingStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/**
 * The service without Spring and without anything real: in-memory store, a clock that stands still
 * (Monday 5 October 2026, 09:00 Berlin) and recording fakes for webhook, mail, calendar.
 */
public final class Harness {

    public static final RequestContext LIVE = new RequestContext(false);
    public static final RequestContext SANDBOX = new RequestContext(true);
    public static final String TUESDAY_9 = "2026-10-06T07:00:00.000Z";

    public final CalConfig config;
    public final InMemoryBookingStore store = new InMemoryBookingStore();
    public final MutableClock clock = new MutableClock();
    public final RecordingWebhookSender webhooks = new RecordingWebhookSender();
    public final RecordingNotifier notifier = new RecordingNotifier();
    public final RecordingCalendar calendar = new RecordingCalendar();
    public final StaticBusyTimes busyTimes = new StaticBusyTimes();
    public final OutboxDispatcher dispatcher;
    public final BookingService service;
    public final JsonMapper mapper = JsonMapper.builder().build();

    public Harness() {
        this(new Fixtures.Props());
    }

    public Harness(Fixtures.Props props) {
        config = props.build();
        clock.setFixed(Instant.parse("2026-10-05T07:00:00Z"));
        JsonWriter json = mapper::writeValueAsString;
        dispatcher = new OutboxDispatcher(config, store, clock, webhooks, notifier, calendar, Runnable::run);
        AtomicInteger counter = new AtomicInteger(1);
        service = new BookingService(config, store, clock, busyTimes, dispatcher, json, () -> "test-" + counter.getAndIncrement());
    }

    public static Map<String, Object> bookingBody(String start) {
        Map<String, Object> attendee = new LinkedHashMap<>();
        attendee.put("name", "Erika Beispiel");
        attendee.put("email", "erika@example.com");
        attendee.put("timeZone", "Europe/Berlin");
        attendee.put("language", "de");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "dsb-potenzialanalyse");
        metadata.put("intakeHash", "hash_1");
        metadata.put("tenantId", "tenant_1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("start", start);
        body.put("eventTypeId", 1);
        body.put("attendee", attendee);
        body.put("metadata", metadata);
        return body;
    }

    public Booking reserve(String start, RequestContext context) {
        return service.createBooking(bookingBody(start), context);
    }

    /** Reserves Tuesday 09:00 Berlin and confirms it. */
    public Booking reserveAndConfirm(RequestContext context) {
        return service.confirmBooking(reserve(TUESDAY_9, context).uid(), context);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String json) {
        return mapper.readValue(json, Map.class);
    }
}
