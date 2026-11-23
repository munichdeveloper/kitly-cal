package de.atstck.kitly.cal.store;

import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.BookingStatus;
import de.atstck.kitly.cal.domain.Interval;
import de.atstck.kitly.cal.domain.NewBooking;
import de.atstck.kitly.cal.domain.NewOutboxEvent;
import de.atstck.kitly.cal.domain.OutboxEvent;
import de.atstck.kitly.cal.domain.OutboxStatus;
import de.atstck.kitly.cal.domain.SlotTakenException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory store for tests and the mock profile. Not for production: a restart forgets every
 * booking, and slots that are already sold would be offered again.
 */
public class InMemoryBookingStore implements BookingStore {

    private final List<Booking> bookings = new ArrayList<>();
    private final List<OutboxEvent> outbox = new ArrayList<>();
    private final Map<Long, Instant> locks = new HashMap<>();
    private long nextBookingId = 1;
    private long nextOutboxId = 1;

    @Override
    public synchronized Booking createBooking(NewBooking input) {
        Interval block = new Interval(input.blockStart(), input.blockEnd());
        boolean taken = bookings.stream().anyMatch(other -> other.sandbox() == input.sandbox()
                && other.status().blocksSlot()
                && other.blockRange().overlaps(block));
        if (taken) {
            throw new SlotTakenException();
        }
        Booking booking = new Booking(nextBookingId++, input.uid(), input.sandbox(), input.eventTypeId(),
                BookingStatus.PENDING, input.start(), input.end(), input.blockStart(), input.blockEnd(),
                input.attendee(), Map.copyOf(input.metadata()), null, input.createdAt(), input.createdAt(), null, null);
        bookings.add(booking);
        return booking;
    }

    @Override
    public synchronized Optional<Booking> getBooking(String uid, boolean sandbox) {
        return bookings.stream().filter(booking -> booking.uid().equals(uid) && booking.sandbox() == sandbox).findFirst();
    }

    @Override
    public synchronized Optional<TransitionResult> transition(Transition transition) {
        Optional<Booking> found = getBooking(transition.uid(), transition.sandbox());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Booking current = found.get();
        if (current.status() != transition.from()) {
            return Optional.of(new TransitionResult(current, false));
        }
        Booking updated = current.withTransition(transition.to(), transition.at(), transition.reason());
        bookings.set(bookings.indexOf(current), updated);
        for (NewOutboxEvent event : transition.outbox()) {
            outbox.add(new OutboxEvent(nextOutboxId++, updated.uid(), updated.sandbox(), event.kind(), event.payload(),
                    OutboxStatus.PENDING, 0, transition.at(), null, transition.at(), null));
        }
        return Optional.of(new TransitionResult(updated, true));
    }

    @Override
    public synchronized List<Booking> listBookings(BookingFilter filter) {
        return bookings.stream()
                .filter(booking -> booking.sandbox() == filter.sandbox())
                .filter(booking -> filter.statuses() == null || filter.statuses().contains(booking.status()))
                .filter(booking -> filter.endsAfter() == null || booking.end().isAfter(filter.endsAfter()))
                .filter(booking -> filter.endsBefore() == null || !booking.end().isAfter(filter.endsBefore()))
                .sorted(Comparator.comparing(Booking::createdAt).thenComparing(Booking::id))
                .skip(filter.skip())
                .limit(filter.take())
                .toList();
    }

    @Override
    public synchronized List<Interval> listBlockingIntervals(Interval range, boolean sandbox) {
        return bookings.stream()
                .filter(booking -> booking.sandbox() == sandbox && booking.status().blocksSlot())
                .map(Booking::blockRange)
                .filter(block -> block.overlaps(range))
                .toList();
    }

    @Override
    public synchronized List<OutboxEvent> claimOutbox(Instant now, int limit, Duration lease) {
        List<OutboxEvent> due = outbox.stream()
                .filter(event -> event.status() == OutboxStatus.PENDING && !event.nextAttemptAt().isAfter(now))
                .filter(event -> !locks.containsKey(event.id()) || locks.get(event.id()).isBefore(now))
                .limit(limit)
                .toList();
        due.forEach(event -> locks.put(event.id(), now.plus(lease)));
        return due;
    }

    @Override
    public synchronized void recordOutboxSuccess(long id, Instant at) {
        replace(id, event -> new OutboxEvent(event.id(), event.bookingUid(), event.sandbox(), event.kind(), event.payload(),
                OutboxStatus.DELIVERED, event.attempts() + 1, event.nextAttemptAt(), null, event.createdAt(), at));
    }

    @Override
    public synchronized void recordOutboxFailure(long id, Instant at, String error, Instant retryAt) {
        replace(id, event -> new OutboxEvent(event.id(), event.bookingUid(), event.sandbox(), event.kind(), event.payload(),
                retryAt == null ? OutboxStatus.DEAD : OutboxStatus.PENDING, event.attempts() + 1,
                retryAt == null ? event.nextAttemptAt() : retryAt, error, event.createdAt(), null));
    }

    @Override
    public synchronized List<OutboxEvent> listOutbox(OutboxStatus status, int take) {
        List<OutboxEvent> matching = outbox.stream().filter(event -> status == null || event.status() == status).toList();
        return matching.subList(Math.max(0, matching.size() - take), matching.size());
    }

    @Override
    public void ping() {
    }

    /** Forget everything. */
    public synchronized void reset() {
        bookings.clear();
        outbox.clear();
        locks.clear();
        nextBookingId = 1;
        nextOutboxId = 1;
    }

    private void replace(long id, java.util.function.UnaryOperator<OutboxEvent> change) {
        for (int index = 0; index < outbox.size(); index++) {
            if (outbox.get(index).id() == id) {
                outbox.set(index, change.apply(outbox.get(index)));
                locks.remove(id);
                return;
            }
        }
    }
}
