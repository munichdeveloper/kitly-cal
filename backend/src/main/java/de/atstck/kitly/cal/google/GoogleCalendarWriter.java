package de.atstck.kitly.cal.google;

import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.outbox.CalendarWriter;
import de.atstck.kitly.cal.outbox.ConfirmationContext;
import de.atstck.kitly.cal.outbox.OutboxDispatcher.PermanentDeliveryException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a confirmed booking into the host's Google calendar, with the customer as guest and a
 * Google Meet link. With send-updates "all" Google itself e-mails the customer the invitation,
 * which carries date, Meet link and an "add to calendar" button.
 *
 * Retries are safe: the event id is derived from the booking uid, so a second attempt after a
 * timeout meets "409 already exists" and counts as delivered instead of creating a duplicate.
 */
public class GoogleCalendarWriter implements CalendarWriter {

    private final GoogleClient client;
    private final GoogleProperties properties;
    private final GoogleBusyTimes busyTimes;

    /** @param busyTimes may be null; its cache is cleared after each new event */
    public GoogleCalendarWriter(GoogleClient client, GoogleProperties properties, GoogleBusyTimes busyTimes) {
        this.client = client;
        this.properties = properties;
        this.busyTimes = busyTimes;
    }

    @Override
    public boolean acceptsSandbox() {
        return properties.sandboxCalendarEnabled();
    }

    @Override
    public void createEvent(ConfirmationContext context) throws Exception {
        boolean sandbox = context.booking().sandbox();
        if (sandbox && !acceptsSandbox()) {
            throw new PermanentDeliveryException("No test calendar configured for sandbox bookings.");
        }
        // A sandbox booking only ever goes to the test calendar, never to the real one.
        String calendarId = sandbox ? properties.sandboxCalendar() : properties.calendar();
        String updates = sandbox ? properties.sandboxUpdates() : properties.updates();
        String path = "/calendars/" + GoogleClient.encode(calendarId) + "/events"
                + "?sendUpdates=" + GoogleClient.encode(updates)
                + (properties.meet() ? "&conferenceDataVersion=1" : "");
        try {
            client.send("POST", path, event(context, properties.meet()));
        } catch (GoogleClient.GoogleApiException e) {
            if (e.status() == 409) {
                return; // created by an earlier attempt whose answer got lost
            }
            if (e.permanent()) {
                throw new PermanentDeliveryException(e.getMessage());
            }
            throw e;
        } finally {
            if (busyTimes != null && !sandbox) {
                busyTimes.invalidate();
            }
        }
    }

    /** Google event ids allow the characters 0-9 and a-v; lowercase hex of the uid fits. */
    static String eventId(String bookingUid) {
        return "kc" + HexFormat.of().formatHex(bookingUid.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> event(ConfirmationContext context, boolean meet) {
        Booking booking = context.booking();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", eventId(booking.uid()));
        event.put("summary", (booking.sandbox() ? "[TEST] " : "") + context.eventType().title() + " – " + booking.attendee().name());
        event.put("description", "Gebucht über die Website.\n"
                + "Kunde: " + booking.attendee().name() + " <" + booking.attendee().email() + ">\n"
                + "Buchung: " + booking.uid());
        event.put("start", Map.of("dateTime", booking.start().toString()));
        event.put("end", Map.of("dateTime", booking.end().toString()));
        event.put("attendees", List.of(Map.of(
                "email", booking.attendee().email(),
                "displayName", booking.attendee().name())));
        if (context.eventType().location() != null) {
            event.put("location", context.eventType().location());
        }
        event.put("guestsCanInviteOthers", false);
        event.put("guestsCanSeeOtherGuests", false);
        event.put("reminders", Map.of("useDefault", true));
        if (meet) {
            event.put("conferenceData", Map.of("createRequest", Map.of(
                    "requestId", booking.uid(),
                    "conferenceSolutionKey", Map.of("type", "hangoutsMeet"))));
        }
        return event;
    }
}
