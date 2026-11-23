package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.domain.Attendee;
import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.BookingStatus;
import de.atstck.kitly.cal.outbox.ConfirmationContext;
import de.atstck.kitly.cal.outbox.IcsBuilder;
import de.atstck.kitly.cal.support.Fixtures;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IcsBuilderTest {

    private static final String CRLF = String.valueOf((char) 13) + (char) 10;
    private static final String BACKSLASH = String.valueOf((char) 92);

    @Test
    void containsTheAppointmentInUtcWithEscapedTextAndFoldedLines() {
        CalConfig config = Fixtures.config();
        Instant start = Instant.parse("2026-10-06T07:00:00Z");
        Instant end = Instant.parse("2026-10-06T09:00:00Z");
        Booking booking = new Booking(1, "uid-1", false, 1, BookingStatus.ACCEPTED, start, end, start, end,
                new Attendee("Müller; Söhne, GmbH", "kunde@example.com", "Europe/Berlin", "de"), Map.of(), null,
                start, start, start, null);

        String ics = IcsBuilder.build(new ConfirmationContext(booking, config.eventTypes().get(0), config.organizer()),
                Instant.parse("2026-10-05T07:20:00Z"));

        assertThat(ics).startsWith("BEGIN:VCALENDAR" + CRLF).endsWith("END:VCALENDAR" + CRLF);
        assertThat(ics).contains("UID:uid-1@example.com" + CRLF);
        assertThat(ics).contains("DTSTAMP:20261005T072000Z" + CRLF);
        assertThat(ics).contains("DTSTART:20261006T070000Z" + CRLF);
        assertThat(ics).contains("DTEND:20261006T090000Z" + CRLF);
        assertThat(ics).contains("SUMMARY:Potenzialanalyse" + CRLF);
        assertThat(ics).contains("LOCATION:https://meet.example.com/room" + CRLF);
        String unfolded = ics.replace(CRLF + " ", "");
        assertThat(unfolded).contains("ATTENDEE;CN=Müller" + BACKSLASH + "; Söhne" + BACKSLASH + ", GmbH;");
        assertThat(unfolded).contains(":mailto:kunde@example.com" + CRLF);
        for (String line : ics.split(CRLF)) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
    }
}
