package de.atstck.kitly.cal.domain;

import java.time.Instant;

/** Half-open interval [start, end). */
public record Interval(Instant start, Instant end) {

    public boolean overlaps(Interval other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
