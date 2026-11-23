package de.atstck.kitly.cal.service;

import de.atstck.kitly.cal.availability.BusyTimeProvider;
import de.atstck.kitly.cal.availability.SlotCalculator;
import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.config.UidGenerator;
import de.atstck.kitly.cal.domain.Attendee;
import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.BookingStatus;
import de.atstck.kitly.cal.domain.Interval;
import de.atstck.kitly.cal.domain.Iso;
import de.atstck.kitly.cal.domain.NewBooking;
import de.atstck.kitly.cal.domain.NewOutboxEvent;
import de.atstck.kitly.cal.domain.OutboxKind;
import de.atstck.kitly.cal.domain.ServiceException;
import de.atstck.kitly.cal.domain.SlotTakenException;
import de.atstck.kitly.cal.outbox.OutboxDispatcher;
import de.atstck.kitly.cal.outbox.Wire;
import de.atstck.kitly.cal.store.BookingStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import de.atstck.kitly.cal.config.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The use cases: read slots, reserve, confirm, release, list. */
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final Duration DAY = Duration.ofDays(1);
    private static final int MAX_SLOT_RANGE_DAYS = 62;
    private static final String SLOT_UNAVAILABLE = "User either already has booking at this time or is not available";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CalConfig config;
    private final BookingStore store;
    private final Clock clock;
    private final BusyTimeProvider busyTimes;
    private final OutboxDispatcher dispatcher;
    private final JsonWriter json;
    private final UidGenerator uids;

    /**
     * @param busyTimes may be null: then only bookings of this service block a slot
     * @param json      serialises a webhook body
     * @param uids      may be null: then random uids are generated
     */
    public BookingService(CalConfig config, BookingStore store, Clock clock, BusyTimeProvider busyTimes,
            OutboxDispatcher dispatcher, JsonWriter json, UidGenerator uids) {
        this.config = config;
        this.store = store;
        this.clock = clock;
        this.busyTimes = busyTimes;
        this.dispatcher = dispatcher;
        this.json = json;
        this.uids = uids != null ? uids : BookingService::randomUid;
    }

    public record Slots(ZoneId timeZone, List<Interval> slots) {
    }

    public Slots getSlots(Map<String, String> query, RequestContext context) {
        CalConfig.EventType eventType = requireEventType(query.get("eventTypeId"));
        Instant rangeStart = parseInstant(query.get("start"), "start", false);
        Instant rangeEnd = parseInstant(query.get("end"), "end", true);
        if (rangeEnd.isBefore(rangeStart)) {
            throw ServiceException.badRequest("end must not be before start.");
        }
        if (Duration.between(rangeStart, rangeEnd).compareTo(Duration.ofDays(MAX_SLOT_RANGE_DAYS)) > 0) {
            throw ServiceException.badRequest("The range must not be longer than " + MAX_SLOT_RANGE_DAYS + " days.");
        }
        ZoneId timeZone = query.get("timeZone") == null ? ZoneOffset.UTC : parseZone(query.get("timeZone"), "timeZone");

        List<Interval> busy = busyIn(new Interval(rangeStart.minus(DAY), rangeEnd.plus(DAY)), context);
        List<Interval> slots = SlotCalculator.compute(
                new SlotCalculator.Query(eventType, config, rangeStart, rangeEnd, clock.instant(), busy));
        return new Slots(timeZone, slots);
    }

    /** Reserves a slot. The booking starts as pending: it blocks the slot but nothing is sent to anyone. */
    public Booking createBooking(Map<String, Object> body, RequestContext context) {
        if (body == null) {
            throw ServiceException.badRequest("Request body must be a JSON object.");
        }
        CalConfig.EventType eventType = requireEventType(body.get("eventTypeId"));
        if (!(body.get("start") instanceof String startRaw)) {
            throw ServiceException.badRequest("start must be an ISO 8601 date-time with Z or a UTC offset.");
        }
        Instant start;
        try {
            start = OffsetDateTime.parse(startRaw).toInstant();
        } catch (DateTimeException e) {
            throw ServiceException.badRequest("start must be an ISO 8601 date-time with Z or a UTC offset.");
        }

        Map<?, ?> attendeeRaw = body.get("attendee") instanceof Map<?, ?> map ? map : Map.of();
        String name = attendeeRaw.get("name") instanceof String value ? value.trim() : "";
        String email = attendeeRaw.get("email") instanceof String value ? value.trim() : "";
        String language = attendeeRaw.get("language") instanceof String value && !value.isBlank() ? value : "de";
        if (name.isEmpty() || name.length() > 200) {
            throw ServiceException.badRequest("attendee.name is required (at most 200 characters).");
        }
        if (!looksLikeEmail(email)) {
            throw ServiceException.badRequest("attendee.email must be a valid e-mail address.");
        }
        if (!(attendeeRaw.get("timeZone") instanceof String attendeeZone)) {
            throw ServiceException.badRequest("attendee.timeZone must be a valid IANA time zone.");
        }
        parseZone(attendeeZone, "attendee.timeZone");
        if (language.length() > 10) {
            throw ServiceException.badRequest("attendee.language is too long.");
        }
        Map<String, String> metadata = parseMetadata(body.get("metadata"));

        Instant now = clock.instant();
        if (start.isBefore(now)) {
            throw ServiceException.badRequest("Attempting to book a meeting in the past.");
        }

        Interval block = SlotCalculator.blockRange(start, eventType);
        List<Interval> busy = busyIn(block, context);
        if (!SlotCalculator.isBookableStart(start, eventType, config, now, busy)) {
            throw ServiceException.badRequest(SLOT_UNAVAILABLE);
        }

        try {
            Booking booking = store.createBooking(new NewBooking(
                    uids.next(), context.sandbox(), eventType.id(), start,
                    start.plus(Duration.ofMinutes(eventType.durationMinutes())), block.start(), block.end(),
                    new Attendee(name, email, attendeeZone, language), metadata, now));
            log.info("booking reserved uid={} sandbox={} start={}", booking.uid(), booking.sandbox(), Iso.utc(booking.start()));
            return booking;
        } catch (SlotTakenException e) {
            // Lost the race against a parallel request for the same slot.
            throw ServiceException.badRequest(SLOT_UNAVAILABLE);
        }
    }

    public Booking getBooking(String uid, RequestContext context) {
        return store.getBooking(uid, context.sandbox())
                .orElseThrow(() -> ServiceException.notFound("Booking with uid " + uid + " not found."));
    }

    /**
     * pending -> accepted. Only now the outside world hears about the booking (webhook, mail, calendar).
     * Confirming an already accepted booking succeeds again without repeating any effect.
     */
    public Booking confirmBooking(String uid, RequestContext context) {
        Booking current = getBooking(uid, context);
        if (current.status() == BookingStatus.ACCEPTED) {
            return current;
        }
        if (current.status() == BookingStatus.REJECTED) {
            throw notConfirmable(current.status());
        }
        CalConfig.EventType eventType = config.eventType(current.eventTypeId()).orElseThrow(() -> new ServiceException(
                500, "CONFIGURATION", "Event type " + current.eventTypeId() + " is no longer configured."));

        Instant at = clock.instant();
        List<NewOutboxEvent> outbox = new ArrayList<>();
        if (config.webhook().urlFor(context.sandbox()) != null) {
            Booking accepted = current.withTransition(BookingStatus.ACCEPTED, at, null);
            outbox.add(new NewOutboxEvent(OutboxKind.WEBHOOK,
                    json.write(Wire.bookingCreated(accepted, eventType, config, at))));
        }
        if (!context.sandbox() && dispatcher.hasNotifier()) {
            outbox.add(new NewOutboxEvent(OutboxKind.NOTIFICATION, "{}"));
        }
        if (!context.sandbox() && dispatcher.hasCalendar()) {
            outbox.add(new NewOutboxEvent(OutboxKind.CALENDAR, "{}"));
        }

        BookingStore.TransitionResult result = store
                .transition(new BookingStore.Transition(uid, context.sandbox(), BookingStatus.PENDING,
                        BookingStatus.ACCEPTED, at, null, outbox))
                .orElseThrow(() -> ServiceException.notFound("Booking with uid " + uid + " not found."));
        if (!result.changed()) {
            // A parallel request was faster: either the same confirmation or a decline.
            if (result.booking().status() == BookingStatus.ACCEPTED) {
                return result.booking();
            }
            throw notConfirmable(result.booking().status());
        }

        log.info("booking confirmed uid={} sandbox={} effects={}", uid, context.sandbox(),
                outbox.stream().map(event -> event.kind().wire()).toList());
        if (!outbox.isEmpty()) {
            dispatcher.afterConfirm();
        }
        return result.booking();
    }

    /**
     * pending -> rejected, which frees the slot. Refuses everything that is not pending, so a booking
     * that was just paid and confirmed can never be released by the housekeeping job.
     */
    public Booking declineBooking(String uid, Map<String, Object> body, RequestContext context) {
        Object reasonRaw = body == null ? null : body.get("reason");
        if (reasonRaw != null && !(reasonRaw instanceof String text && text.length() <= 500)) {
            throw ServiceException.badRequest("reason must be a string of at most 500 characters.");
        }
        String reason = reasonRaw instanceof String text && !text.isEmpty() ? text : null;

        BookingStore.TransitionResult result = store
                .transition(new BookingStore.Transition(uid, context.sandbox(), BookingStatus.PENDING,
                        BookingStatus.REJECTED, clock.instant(), reason, List.of()))
                .orElseThrow(() -> ServiceException.notFound("Booking with uid " + uid + " not found."));
        if (!result.changed()) {
            throw ServiceException.badRequest(
                    "Booking is not pending (status: " + result.booking().status().wire() + ") and cannot be declined.");
        }
        log.info("booking declined uid={} sandbox={}", uid, context.sandbox());
        return result.booking();
    }

    public List<Booking> listBookings(Map<String, String> query, RequestContext context) {
        int take = parseInt(query.get("take"), 100);
        int skip = parseInt(query.get("skip"), 0);
        if (take < 1 || take > 250) {
            throw ServiceException.badRequest("take must be between 1 and 250.");
        }
        if (skip < 0) {
            throw ServiceException.badRequest("skip must be 0 or greater.");
        }
        Instant now = clock.instant();
        String status = query.get("status");
        BookingStore.BookingFilter filter;
        if (status == null) {
            filter = new BookingStore.BookingFilter(context.sandbox(), null, null, null, take, skip);
        } else {
            filter = switch (status) {
                case "unconfirmed" -> new BookingStore.BookingFilter(
                        context.sandbox(), List.of(BookingStatus.PENDING), null, null, take, skip);
                case "upcoming" -> new BookingStore.BookingFilter(
                        context.sandbox(), List.of(BookingStatus.ACCEPTED), now, null, take, skip);
                case "past" -> new BookingStore.BookingFilter(
                        context.sandbox(), List.of(BookingStatus.ACCEPTED), null, now, take, skip);
                case "cancelled" -> new BookingStore.BookingFilter(
                        context.sandbox(), List.of(BookingStatus.REJECTED), null, null, take, skip);
                default -> throw ServiceException.badRequest(
                        "status must be one of: unconfirmed, upcoming, past, cancelled.");
            };
        }
        return store.listBookings(filter);
    }

    /** Everything that occupies the host in the range: own bookings plus the external calendar. */
    private List<Interval> busyIn(Interval range, RequestContext context) {
        List<Interval> busy = new ArrayList<>(store.listBlockingIntervals(range, context.sandbox()));
        if (busyTimes == null) {
            return busy;
        }
        try {
            busy.addAll(busyTimes.getBusy(range));
            return busy;
        } catch (Exception e) {
            if (config.busyTimeFailureMode() == CalConfig.FailureMode.OPEN) {
                log.warn("external calendar unavailable, continuing without it: {}", e.getMessage());
                return busy;
            }
            log.error("external calendar unavailable, offering no slots: {}", e.getMessage());
            throw new ServiceException(503, "CALENDAR_UNAVAILABLE", "Availability cannot be determined right now.");
        }
    }

    private CalConfig.EventType requireEventType(Object raw) {
        Integer id = null;
        if (raw instanceof Integer number) {
            id = number;
        } else if (raw instanceof String text) {
            try {
                id = Integer.valueOf(text.trim());
            } catch (NumberFormatException e) {
                id = null;
            }
        }
        if (id == null) {
            throw ServiceException.badRequest("eventTypeId is required and must be a number.");
        }
        int wanted = id;
        return config.eventType(wanted)
                .orElseThrow(() -> ServiceException.notFound("Event type with id " + wanted + " not found."));
    }

    private static Instant parseInstant(String value, String name, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            throw ServiceException.badRequest(name + " is required.");
        }
        try {
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value);
                Instant startOfDay = date.atStartOfDay(ZoneOffset.UTC).toInstant();
                return endOfDay ? startOfDay.plus(DAY).minusMillis(1) : startOfDay;
            }
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException e) {
            throw ServiceException.badRequest(
                    name + " must be an ISO 8601 date or a date-time with Z or a UTC offset.");
        }
    }

    private static ZoneId parseZone(String value, String name) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            throw ServiceException.badRequest(name + " must be a valid IANA time zone.");
        }
    }

    private static Map<String, String> parseMetadata(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw ServiceException.badRequest("metadata must be an object.");
        }
        if (map.size() > 50) {
            throw ServiceException.badRequest("metadata must not have more than 50 keys.");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.length() > 40) {
                throw ServiceException.badRequest("metadata keys must not be longer than 40 characters.");
            }
            if (!(entry.getValue() instanceof String value) || value.length() > 500) {
                throw ServiceException.badRequest("metadata values must be strings of at most 500 characters.");
            }
            metadata.put(key, value);
        }
        return metadata;
    }

    private static boolean looksLikeEmail(String email) {
        int at = email.indexOf('@');
        return email.length() <= 254
                && at > 0
                && at == email.lastIndexOf('@')
                && email.indexOf('.', at + 2) > 0
                && !email.endsWith(".")
                && email.chars().noneMatch(Character::isWhitespace);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ServiceException notConfirmable(BookingStatus status) {
        return ServiceException.badRequest(
                "Booking is " + status.wire() + " and cannot be confirmed. The reservation was released.");
    }

    private static String randomUid() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
