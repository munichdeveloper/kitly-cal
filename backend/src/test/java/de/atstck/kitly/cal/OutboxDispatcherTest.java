package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;

import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.BookingStatus;
import de.atstck.kitly.cal.domain.NewOutboxEvent;
import de.atstck.kitly.cal.domain.OutboxEvent;
import de.atstck.kitly.cal.domain.OutboxKind;
import de.atstck.kitly.cal.domain.OutboxStatus;
import de.atstck.kitly.cal.outbox.OutboxDispatcher;
import de.atstck.kitly.cal.outbox.OutboxDispatcher.Summary;
import de.atstck.kitly.cal.outbox.WebhookSender.WebhookRequest;
import de.atstck.kitly.cal.outbox.Wire;
import de.atstck.kitly.cal.store.BookingStore;
import de.atstck.kitly.cal.support.Fixtures;
import de.atstck.kitly.cal.support.Harness;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboxDispatcherTest {

    private final Harness harness = new Harness();

    private OutboxEvent event(Harness target, OutboxKind kind) {
        return target.store.listOutbox(null, 50).stream().filter(item -> item.kind() == kind).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsBookingCreatedSignedTheWayTheAppVerifiesIt() {
        Booking booking = harness.reserveAndConfirm(Harness.LIVE);

        assertThat(harness.webhooks.sent()).hasSize(1);
        WebhookRequest request = harness.webhooks.sent().get(0);
        assertThat(request.url()).isEqualTo(Fixtures.WEBHOOK_URL);
        assertThat(request.headers().get("x-cal-signature-256")).isEqualTo(Wire.sign(request.body(), Fixtures.WEBHOOK_SECRET));
        assertThat(request.headers().get("x-cal-webhook-version")).isEqualTo("2021-10-20");
        assertThat(request.headers().get("Content-Type")).isEqualTo("application/json");

        Map<String, Object> body = harness.parse(request.body());
        assertThat(body.get("triggerEvent")).isEqualTo("BOOKING_CREATED");
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        // Exactly the fields lib/cal-webhook.ts reads.
        assertThat(payload.get("uid")).isEqualTo(booking.uid());
        assertThat(payload.get("startTime")).isEqualTo(Harness.TUESDAY_9);
        assertThat(payload.get("status")).isEqualTo("ACCEPTED");
        assertThat(((List<Map<String, Object>>) payload.get("attendees")).get(0))
                .containsEntry("email", "erika@example.com").containsEntry("name", "Erika Beispiel");
        assertThat((Map<String, Object>) payload.get("metadata"))
                .containsEntry("tenantId", "tenant_1").containsEntry("intakeHash", "hash_1");
        assertThat(payload).doesNotContainKey("sandbox");
    }

    @Test
    void matchesSignaturesComputedIndependently() {
        // Reference values from Python's hmac module (HMAC-SHA256, lowercase hex).
        assertThat(Wire.sign("{}", "secret")).isEqualTo("77325902caca812dc259733aacd046b73817372c777b8d95b402647474516e13");
        assertThat(Wire.sign("{\"triggerEvent\":\"PING\",\"n\":\"Müller\"}", "geheim-ä"))
                .isEqualTo("c4327d0e7309e72104b90baf6a5b6d15459655b0fac0a340410db5e7d5b7b230");
    }

    @Test
    void sendsMailAndCalendarEntryExactlyOnceAndOnlyOnConfirm() {
        Booking pending = harness.reserve(Harness.TUESDAY_9, Harness.LIVE);
        assertThat(harness.webhooks.attempts()).isEmpty();
        assertThat(harness.notifier.confirmations()).isEmpty();
        assertThat(harness.calendar.events()).isEmpty();

        harness.service.confirmBooking(pending.uid(), Harness.LIVE);
        harness.service.confirmBooking(pending.uid(), Harness.LIVE);
        harness.dispatcher.runOnce(20);

        assertThat(harness.webhooks.sent()).hasSize(1);
        assertThat(harness.notifier.confirmations()).hasSize(1);
        assertThat(harness.notifier.confirmations().get(0).ics()).contains("DTSTART:20261006T070000Z");
        assertThat(harness.calendar.events()).hasSize(1);
    }

    @Test
    void keepsTheConfirmationValidWhenTheReceiverIsDownAndRetriesLater() {
        harness.webhooks.failWith(new IllegalStateException("Receiver answered 500"));
        Booking booking = harness.reserveAndConfirm(Harness.LIVE);
        assertThat(booking.status()).isEqualTo(BookingStatus.ACCEPTED);
        OutboxEvent failed = event(harness, OutboxKind.WEBHOOK);
        assertThat(failed.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("Receiver answered 500");
        // Mail and calendar entry do not depend on the webhook.
        assertThat(harness.notifier.confirmations()).hasSize(1);
        assertThat(harness.calendar.events()).hasSize(1);

        // Not due yet: nothing happens.
        harness.clock.advance(Duration.ofSeconds(15));
        harness.dispatcher.runOnce(20);
        assertThat(harness.webhooks.attempts()).hasSize(1);

        harness.webhooks.failWith(null);
        harness.clock.advance(OutboxDispatcher.RETRY_DELAYS.get(0));
        assertThat(harness.dispatcher.runOnce(20)).isEqualTo(new Summary(1, 0, 0));
        assertThat(harness.webhooks.sent()).hasSize(1);
        assertThat(event(harness, OutboxKind.WEBHOOK).status()).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(event(harness, OutboxKind.WEBHOOK).attempts()).isEqualTo(2);

        // Delivered events are never sent again.
        harness.clock.advance(Duration.ofDays(1));
        harness.dispatcher.runOnce(20);
        assertThat(harness.webhooks.attempts()).hasSize(2);
    }

    @Test
    void usesTheSameDeliveryIdAndBodyOnEveryRetry() {
        harness.webhooks.failWith(new IllegalStateException("timeout"));
        harness.reserveAndConfirm(Harness.LIVE);
        harness.clock.advance(Duration.ofMinutes(1));
        harness.dispatcher.runOnce(20);

        List<WebhookRequest> attempts = harness.webhooks.attempts();
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).headers().get("x-kitly-cal-delivery-id"))
                .isNotBlank().isEqualTo(attempts.get(1).headers().get("x-kitly-cal-delivery-id"));
        assertThat(attempts.get(0).body()).isEqualTo(attempts.get(1).body());
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfAttemptsAndMarksTheEventDead() {
        Fixtures.Props props = new Fixtures.Props();
        props.maxAttempts = 3;
        Harness limited = new Harness(props);
        limited.webhooks.failWith(new IllegalStateException("Receiver answered 500"));
        limited.reserveAndConfirm(Harness.LIVE);
        for (int run = 0; run < 5; run++) {
            limited.clock.advance(Duration.ofHours(7));
            limited.dispatcher.runOnce(20);
        }
        assertThat(limited.webhooks.attempts()).hasSize(3);
        assertThat(limited.store.listOutbox(OutboxStatus.DEAD, 10)).extracting(OutboxEvent::kind).containsExactly(OutboxKind.WEBHOOK);
    }

    @Test
    void retriesAFailedConfirmationMailWithoutResendingTheWebhook() {
        harness.notifier.failWith(new IllegalStateException("SMTP down"));
        harness.reserveAndConfirm(Harness.LIVE);
        assertThat(harness.webhooks.sent()).hasSize(1);
        assertThat(event(harness, OutboxKind.NOTIFICATION).attempts()).isEqualTo(1);

        harness.notifier.failWith(null);
        harness.clock.advance(Duration.ofMinutes(1));
        harness.dispatcher.runOnce(20);
        assertThat(harness.notifier.confirmations()).hasSize(1);
        assertThat(harness.webhooks.attempts()).hasSize(1);
    }

    @Test
    void sandboxBookingsNeverTriggerMailCalendarEntryOrTheLiveWebhook() {
        Booking confirmed = harness.reserveAndConfirm(Harness.SANDBOX);
        assertThat(confirmed.status()).isEqualTo(BookingStatus.ACCEPTED);
        assertThat(harness.webhooks.attempts()).isEmpty();
        assertThat(harness.notifier.confirmations()).isEmpty();
        assertThat(harness.calendar.events()).isEmpty();
        // Not even queued: there is nothing a later retry could send.
        assertThat(harness.store.listOutbox(null, 10)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sandboxBookingsSendTheirWebhookToTheSandboxUrlWhenOneIsConfigured() {
        Fixtures.Props props = new Fixtures.Props();
        props.sandboxWebhookUrl = "https://app.test/api/cal/webhook-test";
        Harness withTarget = new Harness(props);
        withTarget.reserveAndConfirm(Harness.SANDBOX);

        assertThat(withTarget.webhooks.sent()).extracting(WebhookRequest::url).containsExactly("https://app.test/api/cal/webhook-test");
        Map<String, Object> payload = (Map<String, Object>) withTarget.parse(withTarget.webhooks.sent().get(0).body()).get("payload");
        assertThat(payload.get("sandbox")).isEqualTo(true);
        assertThat(withTarget.notifier.confirmations()).isEmpty();
        assertThat(withTarget.calendar.events()).isEmpty();
    }

    @Test
    void refusesToDeliverAMailOrCalendarEffectForASandboxBookingEvenIfOneWasQueued() {
        Booking booking = harness.reserve(Harness.TUESDAY_9, Harness.SANDBOX);
        // Simulates a programming error upstream: the dispatcher is the last line of defence.
        harness.store.transition(new BookingStore.Transition(booking.uid(), true, BookingStatus.PENDING, BookingStatus.ACCEPTED,
                harness.clock.instant(), null,
                List.of(new NewOutboxEvent(OutboxKind.NOTIFICATION, "{}"), new NewOutboxEvent(OutboxKind.CALENDAR, "{}"))));

        assertThat(harness.dispatcher.runOnce(20)).isEqualTo(new Summary(0, 0, 2));
        assertThat(harness.notifier.confirmations()).isEmpty();
        assertThat(harness.calendar.events()).isEmpty();
    }

    @Test
    void sendsNothingInManualModeUntilSomeoneRunsTheOutbox() {
        Fixtures.Props props = new Fixtures.Props();
        props.outboxMode = "manual";
        Harness manual = new Harness(props);
        manual.reserveAndConfirm(Harness.LIVE);
        assertThat(manual.webhooks.attempts()).isEmpty();
        assertThat(manual.dispatcher.runOnce(20)).isEqualTo(new Summary(3, 0, 0));
        assertThat(manual.webhooks.sent()).hasSize(1);
    }

    @Test
    void sendsNoWebhookWhenNoUrlIsConfigured() {
        Fixtures.Props props = new Fixtures.Props();
        props.webhookUrl = null;
        props.webhookSecret = null;
        Harness quiet = new Harness(props);
        quiet.reserveAndConfirm(Harness.LIVE);
        assertThat(quiet.webhooks.attempts()).isEmpty();
        assertThat(quiet.notifier.confirmations()).hasSize(1);
    }
}
