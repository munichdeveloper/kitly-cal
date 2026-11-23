package de.atstck.kitly.cal.domain;

import java.time.Instant;
import java.util.Map;

/**
 * A booking as stored.
 *
 * @param sandbox    sandbox bookings live in their own world: they never block real slots and never
 *                   reach real customers
 * @param blockStart start of the range the booking keeps free: the slot plus its buffers. Two blocking
 *                   bookings of the same world never overlap in [blockStart, blockEnd)
 */
public record Booking(
        long id,
        String uid,
        boolean sandbox,
        int eventTypeId,
        BookingStatus status,
        Instant start,
        Instant end,
        Instant blockStart,
        Instant blockEnd,
        Attendee attendee,
        Map<String, String> metadata,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt,
        Instant rejectedAt) {

    public Interval blockRange() {
        return new Interval(blockStart, blockEnd);
    }

    public Booking withTransition(BookingStatus to, Instant at, String reason) {
        return new Booking(id, uid, sandbox, eventTypeId, to, start, end, blockStart, blockEnd, attendee, metadata,
                to == BookingStatus.REJECTED ? reason : rejectionReason,
                createdAt, at,
                to == BookingStatus.ACCEPTED ? at : confirmedAt,
                to == BookingStatus.REJECTED ? at : rejectedAt);
    }
}
