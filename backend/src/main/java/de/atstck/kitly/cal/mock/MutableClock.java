package de.atstck.kitly.cal.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests can move. By default it is the real time plus an offset, so time keeps flowing;
 * {@link #setFixed} freezes it at an instant.
 */
public class MutableClock extends Clock {

    private Instant fixed;
    private Duration offset = Duration.ZERO;

    @Override
    public synchronized Instant instant() {
        return fixed != null ? fixed : Instant.now().plus(offset);
    }

    /** Freezes the clock at the instant. */
    public synchronized void setFixed(Instant instant) {
        fixed = instant;
    }

    /** Jumps to the instant and lets time flow on from there. */
    public synchronized void jumpTo(Instant instant) {
        fixed = null;
        offset = Duration.between(Instant.now(), instant);
    }

    public synchronized void advance(Duration duration) {
        if (fixed != null) {
            fixed = fixed.plus(duration);
        } else {
            offset = offset.plus(duration);
        }
    }

    /** Back to real time. */
    public synchronized void reset() {
        fixed = null;
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
