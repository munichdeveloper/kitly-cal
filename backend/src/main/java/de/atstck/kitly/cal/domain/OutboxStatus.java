package de.atstck.kitly.cal.domain;

import java.util.Locale;

public enum OutboxStatus {
    PENDING, DELIVERED,
    /** Gave up. A confirmed customer is missing an effect and a human has to look. */
    DEAD;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static OutboxStatus fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
