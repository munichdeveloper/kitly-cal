package de.atstck.kitly.cal.domain;

import java.util.Locale;

public enum OutboxKind {
    WEBHOOK, NOTIFICATION, CALENDAR;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static OutboxKind fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
