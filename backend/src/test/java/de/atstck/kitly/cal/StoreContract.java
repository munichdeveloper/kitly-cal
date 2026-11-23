package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.atstck.kitly.cal.domain.Attendee;
import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.BookingStatus;
import de.atstck.kitly.cal.domain.Interval;
import de.atstck.kitly.cal.domain.NewBooking;
import de.atstck.kitly.cal.domain.NewOutboxEvent;
import de.atstck.kitly.cal.domain.OutboxEvent;
import de.atstck.kitly.cal.domain.OutboxKind;
import de.atstck.kitly.cal.domain.OutboxStatus;
import de.atstck.kitly.cal.domain.SlotTakenException;
import de.atstck.kitly.cal.store.BookingStore;
import de.atstck.kitly.cal.store.BookingStore.BookingFilter;
import de.atstck.kitly.cal.store.BookingStore.Transition;
import de.atstck.kitly.cal.store.BookingStore.TransitionResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** One contract, every store. Subclasses provide the store under test, empty before each test. */
abstract class StoreContract {

    static final Instant NINE = Instant.parse("2026-10-06T07:00:00Z");
    static final Instant TEN = Instant.parse("2026-10-06T08:00:00Z");
    static final Instant ELEVEN = Instant.parse("2026-10-06T09:00:00Z");
    static final Instant CREATED = Instant.parse("2026-10-05T07:00:00Z");
    static final Instant LATER = Instant.parse("2026-10-05T07:30:00Z");
    static final Duration LEASE = Duration.ofMinutes(1);

    abstract BookingStore store();

    static NewBooking booking(String uid, Instant start) {
        return booking(uid, start, false, start.plus(Duration.ofHours(2)), CREATED);
    }

    static NewBooking booking(String uid, Instant start, boolean sandbox, Instant blockEnd, Instant createdAt) {
        return new NewBooking(uid, sandbox, 1, start, start.plus(Duration.ofHours(2)), start, blockEnd,
                new Attendee("Erika Beispiel", "erika@example.com", "Europe/Berlin", "de"),
                Map.of("source", "dsb-potenzialanalyse"), createdAt);
    }

    Optional<TransitionResult> move(String uid, BookingStatus to, List<NewOutboxEvent> outbox) {
        return store().transition(new Transition(uid, false, BookingStatus.PENDING, to, LATER,
                to == BookingStatus.REJECTED ? "expired" : null, outbox));
    }

    /** Runs the tasks at the same time on separate threads and collects results or failures. */
    static <T> List<Object> inParallel(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Object> results = new ArrayList<>();
            for (Future<T> future : pool.invokeAll(tasks)) {
                try {
                    results.add(future.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    results.add(e.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void storesABookingAsPendingAndReadsItBackUnchanged() {
        Booking created = store().createBooking(booking("a", NINE));
        assertThat(created.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(created.id()).isPositive();
        assertThat(created.start()).isEqualTo(NINE);
        assertThat(created.end()).isEqualTo(ELEVEN);
        assertThat(created.blockRange()).isEqualTo(new Interval(NINE, ELEVEN));
        assertThat(created.updatedAt()).isEqualTo(created.createdAt()).isEqualTo(CREATED);
        assertThat(created.metadata()).isEqualTo(Map.of("source", "dsb-potenzialanalyse"));
        assertThat(created.attendee().email()).isEqualTo("erika@example.com");

        assertThat(store().getBooking("a", false)).contains(created);
        assertThat(store().getBooking("a", true)).isEmpty();
        assertThat(store().getBooking("missing", false)).isEmpty();
    }

    @Test
    void rejectsOverlappingBlockRangesButAllowsBackToBackBookings() {
        store().createBooking(booking("a", NINE));
        assertThatThrownBy(() -> store().createBooking(booking("b", TEN))).isInstanceOf(SlotTakenException.class);
        assertThatThrownBy(() -> store().createBooking(booking("c", NINE))).isInstanceOf(SlotTakenException.class);
        assertThat(store().createBooking(booking("d", ELEVEN)).uid()).isEqualTo("d");
    }

    @Test
    void usesTheBlockRangeNotTheSlotToDecideAboutCollisions() {
        store().createBooking(booking("a", NINE, false, ELEVEN.plus(Duration.ofMinutes(30)), CREATED));
        assertThatThrownBy(() -> store().createBooking(booking("b", ELEVEN))).isInstanceOf(SlotTakenException.class);
    }

    @Test
    void freesTheRangeWhenABookingIsRejectedAndKeepsItWhenAccepted() {
        store().createBooking(booking("a", NINE));
        move("a", BookingStatus.ACCEPTED, List.of());
        assertThatThrownBy(() -> store().createBooking(booking("b", NINE))).isInstanceOf(SlotTakenException.class);

        store().createBooking(booking("c", ELEVEN));
        move("c", BookingStatus.REJECTED, List.of());
        assertThat(store().createBooking(booking("d", ELEVEN)).uid()).isEqualTo("d");
    }

    @Test
    void keepsLiveAndSandboxBookingsFromCollidingWithEachOther() {
        store().createBooking(booking("live", NINE));
        assertThat(store().createBooking(booking("test", NINE, true, ELEVEN, CREATED)).sandbox()).isTrue();
        assertThatThrownBy(() -> store().createBooking(booking("test2", NINE, true, ELEVEN, CREATED)))
                .isInstanceOf(SlotTakenException.class);
    }

    @Test
    void letsExactlyOneOfManyParallelRequestsTakeASlot() throws Exception {
        List<Callable<Booking>> tasks = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            String uid = "race-" + index;
            tasks.add(() -> store().createBooking(booking(uid, NINE)));
        }
        List<Object> results = inParallel(tasks);
        assertThat(results.stream().filter(Booking.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(SlotTakenException.class::isInstance)).hasSize(24);
        assertThat(store().listBookings(new BookingFilter(false, null, null, null, 100, 0))).hasSize(1);
    }

    @Test
    void changesTheStatusOnlyFromTheExpectedStateAndWritesTheOutboxInTheSameStep() {
        store().createBooking(booking("a", NINE));
        List<NewOutboxEvent> outbox = List.of(new NewOutboxEvent(OutboxKind.WEBHOOK, "{\"triggerEvent\":\"BOOKING_CREATED\"}"));

        TransitionResult first = move("a", BookingStatus.ACCEPTED, outbox).orElseThrow();
        assertThat(first.changed()).isTrue();
        assertThat(first.booking().status()).isEqualTo(BookingStatus.ACCEPTED);
        assertThat(first.booking().confirmedAt()).isEqualTo(LATER);
        assertThat(first.booking().updatedAt()).isEqualTo(LATER);
        assertThat(first.booking().rejectedAt()).isNull();

        TransitionResult second = move("a", BookingStatus.ACCEPTED, outbox).orElseThrow();
        assertThat(second.changed()).isFalse();
        TransitionResult declined = move("a", BookingStatus.REJECTED, List.of()).orElseThrow();
        assertThat(declined.changed()).isFalse();
        assertThat(declined.booking().status()).isEqualTo(BookingStatus.ACCEPTED);

        assertThat(move("missing", BookingStatus.ACCEPTED, outbox)).isEmpty();
        assertThat(store().transition(new Transition("a", true, BookingStatus.PENDING, BookingStatus.ACCEPTED, LATER, null, outbox)))
                .isEmpty();

        List<OutboxEvent> events = store().listOutbox(null, 10);
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.bookingUid()).isEqualTo("a");
        assertThat(event.kind()).isEqualTo(OutboxKind.WEBHOOK);
        assertThat(event.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.attempts()).isZero();
        assertThat(event.payload()).isEqualTo("{\"triggerEvent\":\"BOOKING_CREATED\"}");
        assertThat(event.nextAttemptAt()).isEqualTo(LATER);
    }

    @Test
    void recordsTheReasonOfARejection() {
        store().createBooking(booking("a", NINE));
        Booking rejected = move("a", BookingStatus.REJECTED, List.of()).orElseThrow().booking();
        assertThat(rejected.status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(rejected.rejectedAt()).isEqualTo(LATER);
        assertThat(rejected.rejectionReason()).isEqualTo("expired");
        assertThat(rejected.confirmedAt()).isNull();
        assertThat(store().getBooking("a", false)).contains(rejected);
    }

    @Test
    void letsExactlyOneOfAParallelConfirmAndDeclineWinWithAtMostOneWebhook() throws Exception {
        for (int round = 0; round < 10; round++) {
            String uid = "duel-" + round;
            store().createBooking(booking(uid, NINE.plus(Duration.ofDays(round))));
            List<NewOutboxEvent> webhook = List.of(new NewOutboxEvent(OutboxKind.WEBHOOK, uid));
            List<Object> results = inParallel(List.of(
                    () -> move(uid, BookingStatus.ACCEPTED, webhook),
                    () -> move(uid, BookingStatus.REJECTED, List.of()),
                    () -> move(uid, BookingStatus.ACCEPTED, webhook)));

            long winners = results.stream()
                    .filter(result -> result instanceof Optional<?> optional
                            && optional.map(value -> ((TransitionResult) value).changed()).orElse(false))
                    .count();
            assertThat(winners).isEqualTo(1);
            BookingStatus finalStatus = store().getBooking(uid, false).orElseThrow().status();
            long webhooks = store().listOutbox(null, 100).stream().filter(event -> event.bookingUid().equals(uid)).count();
            assertThat(webhooks).isEqualTo(finalStatus == BookingStatus.ACCEPTED ? 1 : 0);
        }
    }

    @Test
    void listsByStatusInAStableOrderWithTakeAndSkip() {
        store().createBooking(booking("a", NINE));
        store().createBooking(booking("b", ELEVEN));
        store().createBooking(booking("c", NINE.plus(Duration.ofDays(1)), false, ELEVEN.plus(Duration.ofDays(1)),
                CREATED.minus(Duration.ofHours(1))));
        store().createBooking(booking("s", NINE, true, ELEVEN, CREATED));
        move("b", BookingStatus.ACCEPTED, List.of());

        assertThat(uids(new BookingFilter(false, null, null, null, 10, 0))).containsExactly("c", "a", "b");
        assertThat(uids(new BookingFilter(false, List.of(BookingStatus.PENDING), null, null, 10, 0))).containsExactly("c", "a");
        assertThat(uids(new BookingFilter(false, List.of(BookingStatus.PENDING), null, null, 1, 1))).containsExactly("a");
        assertThat(uids(new BookingFilter(true, null, null, null, 10, 0))).containsExactly("s");
        Instant ten = Instant.parse("2026-10-06T10:00:00Z");
        assertThat(uids(new BookingFilter(false, List.of(BookingStatus.ACCEPTED), ten, null, 10, 0))).containsExactly("b");
        assertThat(uids(new BookingFilter(false, List.of(BookingStatus.ACCEPTED), null, ten, 10, 0))).isEmpty();
    }

    private List<String> uids(BookingFilter filter) {
        return store().listBookings(filter).stream().map(Booking::uid).toList();
    }

    @Test
    void returnsTheBlockRangesThatTouchARange() {
        store().createBooking(booking("a", NINE));
        store().createBooking(booking("b", NINE.plus(Duration.ofDays(2))));
        store().createBooking(booking("s", ELEVEN, true, ELEVEN.plus(Duration.ofHours(2)), CREATED));
        Interval day = new Interval(Instant.parse("2026-10-06T00:00:00Z"), Instant.parse("2026-10-07T00:00:00Z"));

        assertThat(store().listBlockingIntervals(day, false)).containsExactly(new Interval(NINE, ELEVEN));
        assertThat(store().listBlockingIntervals(new Interval(ELEVEN, ELEVEN.plusMillis(1)), false)).isEmpty();
        assertThat(store().listBlockingIntervals(day, true)).hasSize(1);
    }

    @Test
    void leasesOutboxEventsRetriesFailuresAndStopsAtDead() {
        store().createBooking(booking("a", NINE));
        move("a", BookingStatus.ACCEPTED, List.of(
                new NewOutboxEvent(OutboxKind.WEBHOOK, "w"), new NewOutboxEvent(OutboxKind.NOTIFICATION, "n")));

        assertThat(store().claimOutbox(LATER.minusSeconds(1), 10, LEASE)).isEmpty();
        List<OutboxEvent> claimed = store().claimOutbox(LATER, 10, LEASE);
        assertThat(claimed).extracting(OutboxEvent::kind).containsExactly(OutboxKind.WEBHOOK, OutboxKind.NOTIFICATION);
        assertThat(store().claimOutbox(LATER, 10, LEASE)).isEmpty();
        // A worker that died gives its events back after the lease.
        assertThat(store().claimOutbox(LATER.plus(Duration.ofMinutes(2)), 10, LEASE)).hasSize(2);

        long webhook = claimed.get(0).id();
        long notification = claimed.get(1).id();
        Instant at = LATER.plus(Duration.ofMinutes(3));
        store().recordOutboxSuccess(webhook, at);
        store().recordOutboxFailure(notification, at, "SMTP down", LATER.plus(Duration.ofMinutes(10)));

        assertThat(store().claimOutbox(LATER.plus(Duration.ofMinutes(9)), 10, LEASE)).isEmpty();
        List<OutboxEvent> retry = store().claimOutbox(LATER.plus(Duration.ofMinutes(10)), 10, LEASE);
        assertThat(retry).hasSize(1);
        assertThat(retry.get(0).id()).isEqualTo(notification);
        assertThat(retry.get(0).attempts()).isEqualTo(1);
        assertThat(retry.get(0).lastError()).isEqualTo("SMTP down");

        store().recordOutboxFailure(notification, LATER.plus(Duration.ofMinutes(11)), "still down", null);
        assertThat(store().claimOutbox(LATER.plus(Duration.ofDays(1)), 10, LEASE)).isEmpty();

        List<OutboxEvent> dead = store().listOutbox(OutboxStatus.DEAD, 10);
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).attempts()).isEqualTo(2);
        assertThat(dead.get(0).lastError()).isEqualTo("still down");
        List<OutboxEvent> delivered = store().listOutbox(OutboxStatus.DELIVERED, 10);
        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).id()).isEqualTo(webhook);
        assertThat(delivered.get(0).deliveredAt()).isEqualTo(at);
        assertThat(delivered.get(0).attempts()).isEqualTo(1);
    }

    @Test
    void handsEachDueEventToOnlyOneOfSeveralParallelWorkers() throws Exception {
        for (int index = 0; index < 6; index++) {
            String uid = "w-" + index;
            store().createBooking(booking(uid, NINE.plus(Duration.ofDays(index))));
            move(uid, BookingStatus.ACCEPTED, List.of(new NewOutboxEvent(OutboxKind.WEBHOOK, uid)));
        }
        List<Callable<List<OutboxEvent>>> workers = new ArrayList<>();
        for (int worker = 0; worker < 5; worker++) {
            workers.add(() -> store().claimOutbox(LATER, 4, LEASE));
        }
        List<Long> ids = new ArrayList<>();
        for (Object batch : inParallel(workers)) {
            assertThat(batch).isInstanceOf(List.class);
            ((List<?>) batch).forEach(event -> ids.add(((OutboxEvent) event).id()));
        }
        assertThat(ids).hasSize(6).doesNotHaveDuplicates();
    }

    @Test
    void answersPing() {
        store().ping();
    }
}
