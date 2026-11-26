package de.atstck.kitly.cal.outbox;

/**
 * Writes a confirmed booking into the host's calendar (cal.eu did this in Google Calendar).
 * Implemented by {@code GoogleCalendarWriter}; without a bean of this type no calendar entry is created.
 */
public interface CalendarWriter {

    /** Also called for sandbox bookings when {@link #acceptsSandbox()} is true; check {@code booking().sandbox()}. */
    void createEvent(ConfirmationContext context) throws Exception;

    /** True when this writer has a separate test calendar for sandbox bookings. Never the real calendar. */
    default boolean acceptsSandbox() {
        return false;
    }
}
