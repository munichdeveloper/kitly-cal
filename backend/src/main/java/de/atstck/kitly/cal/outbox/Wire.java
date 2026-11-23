package de.atstck.kitly.cal.outbox;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.Iso;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wire formats. They follow the Cal.com API v2 shapes the app already parses (bookings 2024-08-13,
 * webhook payload version 2021-10-20), so the app needs no code change.
 */
public final class Wire {

    public static final String WEBHOOK_VERSION = "2021-10-20";
    public static final String SIGNATURE_HEADER = "x-cal-signature-256";

    private Wire() {
    }

    private static String title(Booking booking, CalConfig.EventType eventType, CalConfig config) {
        return eventType.title() + " between " + config.organizer().name() + " and " + booking.attendee().name();
    }

    /** Booking as returned by the REST endpoints. */
    public static Map<String, Object> booking(Booking booking, CalConfig config) {
        CalConfig.EventType eventType = config.eventType(booking.eventTypeId()).orElse(null);
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", booking.id());
        wire.put("uid", booking.uid());
        wire.put("title", eventType == null ? "" : title(booking, eventType, config));
        wire.put("description", "");
        wire.put("hosts", List.of(map(
                "id", 1,
                "name", config.organizer().name(),
                "email", config.organizer().email(),
                "timeZone", config.timeZone().getId())));
        wire.put("status", booking.status().wire());
        if (booking.rejectionReason() != null) {
            wire.put("rejectionReason", booking.rejectionReason());
        }
        wire.put("start", Iso.utc(booking.start()));
        wire.put("end", Iso.utc(booking.end()));
        wire.put("duration", Duration.between(booking.start(), booking.end()).toMinutes());
        wire.put("eventTypeId", booking.eventTypeId());
        wire.put("eventType", map("id", booking.eventTypeId(), "slug", eventType == null ? "" : eventType.slug()));
        wire.put("location", eventType == null || eventType.location() == null ? "" : eventType.location());
        wire.put("absentHost", false);
        wire.put("createdAt", Iso.utc(booking.createdAt()));
        wire.put("updatedAt", Iso.utc(booking.updatedAt()));
        wire.put("metadata", booking.metadata());
        wire.put("attendees", List.of(map(
                "name", booking.attendee().name(),
                "email", booking.attendee().email(),
                "timeZone", booking.attendee().timeZone(),
                "language", booking.attendee().language(),
                "absent", false)));
        wire.put("guests", List.of());
        wire.put("bookingFieldsResponses", map("name", booking.attendee().name(), "email", booking.attendee().email()));
        if (booking.sandbox()) {
            wire.put("sandbox", true);
        }
        return wire;
    }

    /** Body of the BOOKING_CREATED webhook that fires once a booking is confirmed. */
    public static Map<String, Object> bookingCreated(Booking booking, CalConfig.EventType eventType, CalConfig config,
            Instant createdAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", eventType.slug());
        payload.put("title", title(booking, eventType, config));
        payload.put("description", "");
        payload.put("additionalNotes", "");
        payload.put("startTime", Iso.utc(booking.start()));
        payload.put("endTime", Iso.utc(booking.end()));
        payload.put("length", eventType.durationMinutes());
        payload.put("organizer", map(
                "name", config.organizer().name(),
                "email", config.organizer().email(),
                "timeZone", config.timeZone().getId(),
                "language", map("locale", "de")));
        payload.put("attendees", List.of(map(
                "name", booking.attendee().name(),
                "email", booking.attendee().email(),
                "timeZone", booking.attendee().timeZone(),
                "language", map("locale", booking.attendee().language()))));
        payload.put("responses", map(
                "name", map("label", "your_name", "value", booking.attendee().name()),
                "email", map("label", "email_address", "value", booking.attendee().email())));
        payload.put("location", eventType.location() == null ? "" : eventType.location());
        payload.put("uid", booking.uid());
        payload.put("bookingId", booking.id());
        payload.put("eventTypeId", booking.eventTypeId());
        payload.put("status", "ACCEPTED");
        payload.put("metadata", booking.metadata());
        if (booking.sandbox()) {
            payload.put("sandbox", true);
        }
        return map("triggerEvent", "BOOKING_CREATED", "createdAt", Iso.utc(createdAt), "payload", payload);
    }

    public static Map<String, Object> ping(Instant createdAt) {
        return map("triggerEvent", "PING", "createdAt", Iso.utc(createdAt), "payload", map("type", "Test"));
    }

    /** Lowercase hex HMAC-SHA256 of the raw body, as verified by lib/cal-webhook.ts in the app. */
    public static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** Headers of a signed webhook request. */
    public static Map<String, String> signedHeaders(String body, String secret) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "kitly-cal");
        headers.put(SIGNATURE_HEADER, sign(body, secret));
        headers.put("x-cal-webhook-version", WEBHOOK_VERSION);
        return headers;
    }

    /** Insertion-ordered map from alternating keys and values. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            result.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return result;
    }
}
