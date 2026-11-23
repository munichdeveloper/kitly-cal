package de.atstck.kitly.cal.domain;

import java.util.Locale;

public enum BookingStatus {
    /** Reserved and unpaid. Blocks the slot, nobody has been told yet. */
    PENDING,
    /** Confirmed after payment. Blocks the slot. */
    ACCEPTED,
    /** Released. The slot is free again. */
    REJECTED;

    /** Pending and accepted bookings keep their slot occupied. */
    public boolean blocksSlot() {
        return this != REJECTED;
    }

    /** Lower-case name as used on the wire and in the database. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BookingStatus fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
