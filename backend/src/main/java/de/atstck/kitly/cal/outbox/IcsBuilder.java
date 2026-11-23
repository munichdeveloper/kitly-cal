package de.atstck.kitly.cal.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the calendar invitation for a confirmed booking. A {@link Notifier} attaches it to the
 * confirmation e-mail, which gives the customer the calendar entry cal.eu used to send.
 */
public final class IcsBuilder {

    private static final String BACKSLASH = String.valueOf((char) 92);
    private static final String LF = String.valueOf((char) 10);
    private static final String CRLF = String.valueOf((char) 13) + LF;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsBuilder() {
    }

    public static String build(ConfirmationContext context, Instant now) {
        String organizerEmail = context.organizer().email();
        int at = organizerEmail.indexOf('@');
        String domain = at >= 0 ? organizerEmail.substring(at + 1) : "kitly-cal";

        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:-//Kitly//Kitly Cal//DE");
        lines.add("CALSCALE:GREGORIAN");
        lines.add("METHOD:REQUEST");
        lines.add("BEGIN:VEVENT");
        lines.add("UID:" + context.booking().uid() + "@" + domain);
        lines.add("DTSTAMP:" + STAMP.format(now));
        lines.add("DTSTART:" + STAMP.format(context.booking().start()));
        lines.add("DTEND:" + STAMP.format(context.booking().end()));
        lines.add("SUMMARY:" + escape(context.eventType().title()));
        if (context.eventType().location() != null) {
            lines.add("LOCATION:" + escape(context.eventType().location()));
        }
        lines.add("ORGANIZER;CN=" + escape(context.organizer().name()) + ":mailto:" + organizerEmail);
        lines.add("ATTENDEE;CN=" + escape(context.booking().attendee().name())
                + ";ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + context.booking().attendee().email());
        lines.add("STATUS:CONFIRMED");
        lines.add("SEQUENCE:0");
        lines.add("END:VEVENT");
        lines.add("END:VCALENDAR");

        StringBuilder ics = new StringBuilder();
        for (String line : lines) {
            ics.append(fold(line)).append(CRLF);
        }
        return ics.toString();
    }

    /** RFC 5545 text escaping: backslash, line breaks, comma and semicolon. */
    static String escape(String value) {
        return value
                .replace(BACKSLASH, BACKSLASH + BACKSLASH)
                .replace(CRLF, BACKSLASH + "n")
                .replace(LF, BACKSLASH + "n")
                .replace(",", BACKSLASH + ",")
                .replace(";", BACKSLASH + ";");
    }

    /** RFC 5545: lines longer than 75 octets continue on the next line after a single space. */
    static String fold(String line) {
        if (line.getBytes(StandardCharsets.UTF_8).length <= 75) {
            return line;
        }
        StringBuilder folded = new StringBuilder();
        int size = 0;
        int limit = 75;
        for (int offset = 0; offset < line.length(); ) {
            int codePoint = line.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterSize = character.getBytes(StandardCharsets.UTF_8).length;
            if (size + characterSize > limit) {
                folded.append(CRLF).append(' ');
                size = 0;
                limit = 74;
            }
            folded.append(character);
            size += characterSize;
            offset += Character.charCount(codePoint);
        }
        return folded.toString();
    }
}
