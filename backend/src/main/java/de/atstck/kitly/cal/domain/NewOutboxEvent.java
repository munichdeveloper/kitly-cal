package de.atstck.kitly.cal.domain;

public record NewOutboxEvent(OutboxKind kind, String payload) {
}
