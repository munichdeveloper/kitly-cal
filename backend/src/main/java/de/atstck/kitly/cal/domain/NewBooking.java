package de.atstck.kitly.cal.domain;

import java.time.Instant;
import java.util.Map;

/** Everything needed to store a new reservation. It always starts as pending. */
public record NewBooking(
        String uid,
        boolean sandbox,
        int eventTypeId,
        Instant start,
        Instant end,
        Instant blockStart,
        Instant blockEnd,
        Attendee attendee,
        Map<String, String> metadata,
        Instant createdAt) {
}
