package de.atstck.kitly.cal.outbox;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.OutboxEvent;
import de.atstck.kitly.cal.domain.OutboxKind;
import de.atstck.kitly.cal.store.BookingStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers the outbox: webhooks, confirmation mails and calendar entries that a confirmed booking owes.
 * Delivery is "at least once": after a timeout the receiver may already have processed the event,
 * and it will then see it again.
 */
public class OutboxDispatcher {

    /** Wait after the 1st, 2nd, ... failed attempt. The last value repeats. */
    public static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(6));

    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final CalConfig config;
    private final BookingStore store;
    private final Clock clock;
    private final WebhookSender webhookSender;
    private final Notifier notifier;
    private final CalendarWriter calendar;
    private final Executor executor;
    private final ReentrantLock running = new ReentrantLock();

    /**
     * @param notifier may be null: then no confirmation is sent to anyone
     * @param calendar may be null: then no calendar entry is created
     * @param executor runs background deliveries
     */
    public OutboxDispatcher(CalConfig config, BookingStore store, Clock clock, WebhookSender webhookSender,
            Notifier notifier, CalendarWriter calendar, Executor executor) {
        this.config = config;
        this.store = store;
        this.clock = clock;
        this.webhookSender = webhookSender;
        this.notifier = notifier;
        this.calendar = calendar;
        this.executor = executor;
    }

    public record Summary(int delivered, int retrying, int dead) {
    }

    public boolean hasNotifier() {
        return notifier != null;
    }

    public boolean hasCalendar() {
        return calendar != null;
    }

    /** True when sandbox bookings get an entry in a separate test calendar. */
    public boolean hasSandboxCalendar() {
        return calendar != null && calendar.acceptsSandbox();
    }

    /** Called after a confirmation wrote new outbox events. */
    public void afterConfirm() {
        switch (config.dispatchMode()) {
            case INLINE -> runOnce(20);
            case BACKGROUND -> executor.execute(this::runQuietly);
            case MANUAL -> {
            }
        }
    }

    /** Delivery run that never throws, for the poller and background threads. */
    public void runQuietly() {
        // A run is already in flight; it will pick up everything that is due.
        if (!running.tryLock()) {
            return;
        }
        try {
            runOnce(20);
        } catch (RuntimeException e) {
            log.error("outbox run failed: {}", e.getMessage());
        } finally {
            running.unlock();
        }
    }

    /** Delivers everything that is due right now. */
    public Summary runOnce(int limit) {
        int delivered = 0;
        int retrying = 0;
        int dead = 0;
        for (OutboxEvent event : store.claimOutbox(clock.instant(), limit, LEASE)) {
            try {
                deliver(event);
                store.recordOutboxSuccess(event.id(), clock.instant());
                delivered++;
                log.info("outbox delivered id={} kind={} bookingUid={}", event.id(), event.kind().wire(), event.bookingUid());
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                int attempt = event.attempts() + 1;
                boolean giveUp = e instanceof PermanentDeliveryException || attempt >= config.webhook().maxAttempts();
                Duration delay = RETRY_DELAYS.get(Math.min(attempt - 1, RETRY_DELAYS.size() - 1));
                Instant now = clock.instant();
                store.recordOutboxFailure(event.id(), now, message.substring(0, Math.min(500, message.length())),
                        giveUp ? null : now.plus(delay));
                if (giveUp) {
                    dead++;
                    // Dead events need a human: the customer is confirmed but an effect never happened.
                    log.error("outbox event is dead id={} kind={} bookingUid={} message={}",
                            event.id(), event.kind().wire(), event.bookingUid(), message);
                } else {
                    retrying++;
                    log.warn("outbox delivery failed, will retry id={} kind={} attempt={} message={}",
                            event.id(), event.kind().wire(), attempt, message);
                }
            }
        }
        return new Summary(delivered, retrying, dead);
    }

    /** Sends a signed PING to the receiver of the given world. Throws when it is not accepted. */
    public void ping(boolean sandbox, String body) throws Exception {
        String url = config.webhook().urlFor(sandbox);
        if (url == null || config.webhook().secret() == null) {
            throw new PermanentDeliveryException("No webhook URL is configured for this key.");
        }
        webhookSender.send(new WebhookSender.WebhookRequest(url, body, Wire.signedHeaders(body, config.webhook().secret())));
    }

    private void deliver(OutboxEvent event) throws Exception {
        if (event.kind() == OutboxKind.WEBHOOK) {
            String url = config.webhook().urlFor(event.sandbox());
            if (url == null || config.webhook().secret() == null) {
                throw new PermanentDeliveryException("No webhook URL or secret configured.");
            }
            Map<String, String> headers = Wire.signedHeaders(event.payload(), config.webhook().secret());
            // Same id on every retry of this event, so a receiver can drop duplicates.
            headers.put("x-kitly-cal-delivery-id", String.valueOf(event.id()));
            webhookSender.send(new WebhookSender.WebhookRequest(url, event.payload(), headers));
            return;
        }

        // Second line of defence: a sandbox booking must never reach a real inbox or calendar.
        // The only exception is a separate test calendar; the writer itself never uses the real one for it.
        if (event.sandbox() && !(event.kind() == OutboxKind.CALENDAR && hasSandboxCalendar())) {
            throw new PermanentDeliveryException("Sandbox bookings have no notification or calendar effect.");
        }
        Booking booking = store.getBooking(event.bookingUid(), event.sandbox())
                .orElseThrow(() -> new PermanentDeliveryException("Booking no longer exists."));
        CalConfig.EventType eventType = config.eventType(booking.eventTypeId())
                .orElseThrow(() -> new PermanentDeliveryException("Event type is no longer configured."));
        ConfirmationContext context = new ConfirmationContext(booking, eventType, config.organizer());

        if (event.kind() == OutboxKind.NOTIFICATION) {
            if (notifier == null) {
                throw new PermanentDeliveryException("No notifier configured.");
            }
            notifier.bookingConfirmed(context, IcsBuilder.build(context, clock.instant()));
            return;
        }
        if (calendar == null) {
            throw new PermanentDeliveryException("No calendar writer configured.");
        }
        calendar.createEvent(context);
    }

    /** A failure that retrying cannot fix (missing configuration, missing data). */
    public static class PermanentDeliveryException extends Exception {

        public PermanentDeliveryException(String message) {
            super(message);
        }
    }
}
