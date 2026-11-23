package de.atstck.kitly.cal.store;

import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.BookingStatus;
import de.atstck.kitly.cal.domain.Interval;
import de.atstck.kitly.cal.domain.NewBooking;
import de.atstck.kitly.cal.domain.NewOutboxEvent;
import de.atstck.kitly.cal.domain.OutboxEvent;
import de.atstck.kitly.cal.domain.OutboxStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port. Two rules every implementation guarantees atomically:
 * <ol>
 *   <li>{@link #createBooking} never stores two blocking bookings (pending or accepted) of the same world
 *       (live or sandbox) with overlapping block ranges. It throws
 *       {@link de.atstck.kitly.cal.domain.SlotTakenException} instead.</li>
 *   <li>{@link #transition} is a compare-and-set on the status and writes its outbox events in the same step.</li>
 * </ol>
 */
public interface BookingStore {

    Booking createBooking(NewBooking booking);

    Optional<Booking> getBooking(String uid, boolean sandbox);

    /**
     * @param outbox written together with the status change, or not at all
     * @return empty when the booking does not exist; {@code changed=false} when it was not in {@code from}
     */
    Optional<TransitionResult> transition(Transition transition);

    /** Stable order (createdAt, id) so take/skip paging never skips or repeats rows. */
    List<Booking> listBookings(BookingFilter filter);

    /** Block ranges of all blocking bookings that touch the range. */
    List<Interval> listBlockingIntervals(Interval range, boolean sandbox);

    /** Hands out due events and leases them, so two workers never deliver the same event at once. */
    List<OutboxEvent> claimOutbox(Instant now, int limit, Duration lease);

    void recordOutboxSuccess(long id, Instant at);

    /** @param retryAt null = give up; the event becomes dead and needs a human */
    void recordOutboxFailure(long id, Instant at, String error, Instant retryAt);

    /** Newest events last. */
    List<OutboxEvent> listOutbox(OutboxStatus status, int take);

    /** Throws when the store is not reachable. */
    void ping();

    record Transition(
            String uid,
            boolean sandbox,
            BookingStatus from,
            BookingStatus to,
            Instant at,
            String reason,
            List<NewOutboxEvent> outbox) {
    }

    record TransitionResult(Booking booking, boolean changed) {
    }

    /**
     * @param statuses   null = all
     * @param endsAfter  only bookings ending after this instant; null = no lower bound
     * @param endsBefore only bookings ending at or before this instant; null = no upper bound
     */
    record BookingFilter(
            boolean sandbox,
            List<BookingStatus> statuses,
            Instant endsAfter,
            Instant endsBefore,
            int take,
            int skip) {
    }
}
