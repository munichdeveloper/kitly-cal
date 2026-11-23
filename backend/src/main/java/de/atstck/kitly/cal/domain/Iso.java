package de.atstck.kitly.cal.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Timestamp formats of the wire protocol. */
public final class Iso {

    private static final DateTimeFormatter UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private Iso() {
    }

    /** UTC with milliseconds, e.g. 2026-10-06T07:00:00.000Z (what JavaScript's toISOString produces). */
    public static String utc(Instant instant) {
        return instant == null ? null : UTC_MILLIS.format(instant);
    }

    /** With the numeric offset of the zone, e.g. 2026-10-14T09:00:00.000+02:00 (Cal API v2 slot format). */
    public static String withOffset(Instant instant, ZoneId zone) {
        return WITH_OFFSET.format(instant.atZone(zone));
    }
}
