package de.atstck.kitly.cal.domain;

import java.time.Instant;

/**
 * A side effect that a confirmed booking owes. It is written in the same transaction as the
 * confirmation and delivered afterwards with retries, so a crash never loses a webhook.
 *
 * @param payload for webhooks the exact raw body that is signed and sent
 */
public record OutboxEvent(
        long id,
        String bookingUid,
        boolean sandbox,
        OutboxKind kind,
        String payload,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant deliveredAt) {
}
