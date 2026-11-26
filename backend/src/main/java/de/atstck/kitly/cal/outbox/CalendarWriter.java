package de.atstck.kitly.cal.outbox;

/**
 * Writes a confirmed booking into the host's calendar (cal.eu did this in Google Calendar).
 * Implemented by {@code GoogleCalendarWriter}; without a bean of this type no calendar entry is created.
 */
public interface CalendarWriter {

    void createEvent(ConfirmationContext context) throws Exception;
}
