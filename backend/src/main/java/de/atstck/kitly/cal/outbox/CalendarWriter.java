package de.atstck.kitly.cal.outbox;

/**
 * Writes a confirmed booking into the host's calendar (cal.eu did this in Google Calendar).
 * No implementation exists yet: declare a Spring bean of this type to switch the effect on.
 */
public interface CalendarWriter {

    void createEvent(ConfirmationContext context) throws Exception;
}
