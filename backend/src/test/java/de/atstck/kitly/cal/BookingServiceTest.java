package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.BookingStatus;
import de.atstck.kitly.cal.domain.ServiceException;
import de.atstck.kitly.cal.service.RequestContext;
import de.atstck.kitly.cal.support.Fixtures;
import de.atstck.kitly.cal.support.Harness;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class BookingServiceTest {

    private static final String TUESDAY_11 = "2026-10-06T09:00:00.000Z";
    private static final Map<String, String> TUESDAY = Map.of(
            "eventTypeId", "1", "start", "2026-10-06T00:00:00.000Z", "end", "2026-10-06T23:59:59.999Z", "timeZone", "Europe/Berlin");

    private final Harness harness = new Harness();

    private List<String> tuesdaySlots(Harness target, RequestContext context) {
        return target.service.getSlots(TUESDAY, context).slots().stream().map(slot -> slot.start().toString()).toList();
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    @Test
    void reservesASlotAsPendingAndTakesItOutOfTheOffer() {
        Booking booking = harness.reserve(Harness.TUESDAY_9, Harness.LIVE);
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.end()).isEqualTo(Instant.parse(TUESDAY_11));
        assertThat(booking.createdAt()).isEqualTo(Instant.parse("2026-10-05T07:00:00Z"));
        assertThat(tuesdaySlots(harness, Harness.LIVE))
                .containsExactly("2026-10-06T09:00:00Z", "2026-10-06T11:00:00Z", "2026-10-06T13:00:00Z");
        assertStatus(() -> harness.reserve(Harness.TUESDAY_9, Harness.LIVE), 400);
    }

    @Test
    void letsOnlyOneOfManySimultaneousRequestsWinTheSameSlot() throws Exception {
        List<Callable<Booking>> tasks = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            tasks.add(() -> harness.reserve(Harness.TUESDAY_9, Harness.LIVE));
        }
        List<Object> results = StoreContract.inParallel(tasks);
        assertThat(results.stream().filter(Booking.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ServiceException.class::isInstance)).hasSize(19);
    }

    @Test
    void refusesStartsThatAreNotAnOfferedSlot() {
        assertStatus(() -> harness.reserve("2026-10-06T07:30:00.000Z", Harness.LIVE), 400); // off the grid
        assertStatus(() -> harness.reserve("2026-10-10T07:00:00.000Z", Harness.LIVE), 400); // Saturday
        assertThatThrownBy(() -> harness.reserve("2026-10-02T07:00:00.000Z", Harness.LIVE)).hasMessageContaining("in the past");
        harness.busyTimes.block(Instant.parse("2026-10-06T09:00:00Z"), Instant.parse("2026-10-06T10:00:00Z"));
        assertStatus(() -> harness.reserve(TUESDAY_11, Harness.LIVE), 400); // blocked by the external calendar
        assertThat(harness.store.listBookings(new de.atstck.kitly.cal.store.BookingStore.BookingFilter(false, null, null, null, 10, 0)))
                .isEmpty();
    }

    @Test
    void acceptsAStartWrittenWithAnOffset() {
        Booking booking = harness.reserve("2026-10-06T09:00:00+02:00", Harness.LIVE);
        assertThat(booking.start()).isEqualTo(Instant.parse(Harness.TUESDAY_9));
    }

    @Test
    void confirmIsRepeatableAndDeclineOnlyWorksWhilePending() {
        Booking booking = harness.reserve(Harness.TUESDAY_9, Harness.LIVE);
        harness.clock.advance(Duration.ofMinutes(20));
        Booking confirmed = harness.service.confirmBooking(booking.uid(), Harness.LIVE);
        assertThat(confirmed.status()).isEqualTo(BookingStatus.ACCEPTED);
        assertThat(confirmed.confirmedAt()).isEqualTo(Instant.parse("2026-10-05T07:20:00Z"));
        assertThat(harness.service.confirmBooking(booking.uid(), Harness.LIVE).status()).isEqualTo(BookingStatus.ACCEPTED);
        assertThat(harness.webhooks.sent()).hasSize(1);

        // The housekeeping job must never release a paid booking.
        assertStatus(() -> harness.service.declineBooking(booking.uid(), Map.of("reason", "expired"), Harness.LIVE), 400);
        assertThat(harness.service.getBooking(booking.uid(), Harness.LIVE).status()).isEqualTo(BookingStatus.ACCEPTED);
        assertThat(tuesdaySlots(harness, Harness.LIVE)).doesNotContain("2026-10-06T07:00:00Z");
    }

    @Test
    void sendsOneWebhookWhenConfirmationsRace() throws Exception {
        Booking booking = harness.reserve(Harness.TUESDAY_9, Harness.LIVE);
        List<Callable<Booking>> tasks = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            tasks.add(() -> harness.service.confirmBooking(booking.uid(), Harness.LIVE));
        }
        assertThat(StoreContract.inParallel(tasks)).allSatisfy(result -> assertThat(result).isInstanceOf(Booking.class));
        harness.dispatcher.runOnce(20);
        assertThat(harness.webhooks.sent()).hasSize(1);
        assertThat(harness.notifier.confirmations()).hasSize(1);
    }

    @Test
    void declineFreesTheSlotAndADeclinedBookingCannotBeConfirmed() {
        Booking booking = harness.reserve(Harness.TUESDAY_9, Harness.LIVE);
        Booking declined = harness.service.declineBooking(booking.uid(), Map.of("reason", "Zahlung nicht abgeschlossen"), Harness.LIVE);
        assertThat(declined.status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(declined.rejectionReason()).isEqualTo("Zahlung nicht abgeschlossen");
        assertThat(tuesdaySlots(harness, Harness.LIVE)).contains("2026-10-06T07:00:00Z");
        assertThat(harness.webhooks.attempts()).isEmpty();

        // The success page turns this into "slot-verloren": choose a new slot, no second payment.
        assertStatus(() -> harness.service.confirmBooking(booking.uid(), Harness.LIVE), 400);
        assertStatus(() -> harness.service.declineBooking(booking.uid(), null, Harness.LIVE), 400);
        assertThat(harness.reserve(Harness.TUESDAY_9, Harness.LIVE).status()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void answersNotFoundForUnknownBookings() {
        assertStatus(() -> harness.service.confirmBooking("nope", Harness.LIVE), 404);
        assertStatus(() -> harness.service.declineBooking("nope", null, Harness.LIVE), 404);
        assertStatus(() -> harness.service.getBooking("nope", Harness.LIVE), 404);
    }

    @Test
    void keepsSandboxAndLiveApartInBothDirections() {
        Booking test = harness.reserve(Harness.TUESDAY_9, Harness.SANDBOX);
        assertThat(test.sandbox()).isTrue();

        // The real world still sees the slot as free and cannot see or touch the test booking.
        assertThat(tuesdaySlots(harness, Harness.LIVE)).contains("2026-10-06T07:00:00Z");
        assertStatus(() -> harness.service.getBooking(test.uid(), Harness.LIVE), 404);
        assertStatus(() -> harness.service.confirmBooking(test.uid(), Harness.LIVE), 404);
        assertStatus(() -> harness.service.declineBooking(test.uid(), null, Harness.LIVE), 404);
        assertThat(harness.service.listBookings(Map.of("status", "unconfirmed"), Harness.LIVE)).isEmpty();

        // The sandbox sees its own booking and blocks the slot for itself only.
        assertThat(tuesdaySlots(harness, Harness.SANDBOX)).doesNotContain("2026-10-06T07:00:00Z");
        assertThat(harness.reserve(Harness.TUESDAY_9, Harness.LIVE).sandbox()).isFalse();
    }

    @Test
    void offersNothingWhenTheExternalCalendarIsUnreachable() {
        harness.busyTimes.failWith(new IllegalStateException("calendar down"));
        assertStatus(() -> harness.service.getSlots(TUESDAY, Harness.LIVE), 503);
        assertStatus(() -> harness.reserve(Harness.TUESDAY_9, Harness.LIVE), 503);
    }

    @Test
    void canBeConfiguredToIgnoreAnUnreachableCalendar() {
        Fixtures.Props props = new Fixtures.Props();
        props.failureMode = "open";
        Harness open = new Harness(props);
        open.busyTimes.failWith(new IllegalStateException("calendar down"));
        assertThat(tuesdaySlots(open, Harness.LIVE)).hasSize(4);
    }

    @Test
    void listsForTheHousekeepingJobInCreationOrder() {
        harness.reserve(Harness.TUESDAY_9, Harness.LIVE);
        harness.clock.advance(Duration.ofMinutes(1));
        Booking second = harness.reserve(TUESDAY_11, Harness.LIVE);
        harness.clock.advance(Duration.ofMinutes(1));
        harness.reserve("2026-10-06T11:00:00.000Z", Harness.LIVE);
        harness.service.confirmBooking(second.uid(), Harness.LIVE);

        assertThat(harness.service.listBookings(Map.of("status", "unconfirmed", "take", "100", "skip", "0"), Harness.LIVE))
                .extracting(Booking::uid).containsExactly("test-1", "test-3");
        assertThat(harness.service.listBookings(Map.of("status", "unconfirmed", "take", "1", "skip", "1"), Harness.LIVE))
                .extracting(Booking::uid).containsExactly("test-3");
        assertThat(harness.service.listBookings(Map.of("status", "upcoming"), Harness.LIVE))
                .extracting(Booking::uid).containsExactly("test-2");
        assertThat(harness.service.listBookings(Map.of(), Harness.LIVE)).hasSize(3);

        assertStatus(() -> harness.service.listBookings(Map.of("status", "everything"), Harness.LIVE), 400);
        assertStatus(() -> harness.service.listBookings(Map.of("take", "0"), Harness.LIVE), 400);
        assertStatus(() -> harness.service.listBookings(Map.of("skip", "-1"), Harness.LIVE), 400);
        assertStatus(() -> harness.service.listBookings(Map.of("take", "many"), Harness.LIVE), 400);
    }

    @Test
    void validatesSlotQueries() {
        assertStatus(() -> harness.service.getSlots(Map.of("start", "2026-10-06", "end", "2026-10-06"), Harness.LIVE), 400);
        assertStatus(() -> harness.service.getSlots(Map.of("eventTypeId", "99", "start", "2026-10-06", "end", "2026-10-06"), Harness.LIVE), 404);
        assertStatus(() -> harness.service.getSlots(Map.of("eventTypeId", "1", "start", "tomorrow", "end", "2026-10-06"), Harness.LIVE), 400);
        assertStatus(() -> harness.service.getSlots(Map.of("eventTypeId", "1", "start", "2026-10-07", "end", "2026-10-06"), Harness.LIVE), 400);
        assertStatus(() -> harness.service.getSlots(Map.of("eventTypeId", "1", "start", "2026-10-01", "end", "2027-03-01"), Harness.LIVE), 400);
        assertStatus(() -> harness.service.getSlots(
                Map.of("eventTypeId", "1", "start", "2026-10-06", "end", "2026-10-06", "timeZone", "Mars/Olympus"), Harness.LIVE), 400);
        // Date-only bounds cover the whole day.
        assertThat(harness.service.getSlots(Map.of("eventTypeId", "1", "start", "2026-10-06", "end", "2026-10-06"), Harness.LIVE).slots())
                .hasSize(4);
    }

    @Test
    void validatesTheBookingBody() {
        List<java.util.function.Consumer<Map<String, Object>>> breakers = List.of(
                body -> body.remove("eventTypeId"),
                body -> body.put("start", "2026-10-06 09:00"),
                body -> body.put("start", 12345),
                body -> body.put("attendee", Map.of("name", "", "email", "erika@example.com", "timeZone", "Europe/Berlin")),
                body -> body.put("attendee", Map.of("name", "Erika", "email", "not-an-email", "timeZone", "Europe/Berlin")),
                body -> body.put("attendee", Map.of("name", "Erika", "email", "erika@example.com", "timeZone", "Nowhere")),
                body -> body.put("attendee", Map.of("name", "Erika", "email", "erika@example.com")),
                body -> body.put("metadata", Map.of("nested", Map.of("no", "objects"))),
                body -> body.put("metadata", Map.of("tooLong", "x".repeat(501))),
                body -> body.put("metadata", "text"));
        for (java.util.function.Consumer<Map<String, Object>> breaker : breakers) {
            Map<String, Object> body = Harness.bookingBody(Harness.TUESDAY_9);
            breaker.accept(body);
            assertStatus(() -> harness.service.createBooking(body, Harness.LIVE), 400);
        }
        Map<String, Object> unknownEventType = Harness.bookingBody(Harness.TUESDAY_9);
        unknownEventType.put("eventTypeId", 99);
        assertStatus(() -> harness.service.createBooking(unknownEventType, Harness.LIVE), 404);
        assertStatus(() -> harness.service.createBooking(null, Harness.LIVE), 400);
    }
}
