package de.atstck.kitly.cal.availability;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.domain.Interval;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Computes bookable slots. Pure: same input, same output.
 *
 * <p>A slot is offered when it lies fully inside an availability window, respects the minimum notice
 * and the booking window, and its block range (slot plus buffers) touches nothing that is busy.
 */
public final class SlotCalculator {

    private SlotCalculator() {
    }

    /**
     * @param rangeStart only slots starting inside [rangeStart, rangeEnd] are returned
     * @param busy       everything that occupies the host: block ranges of bookings and external calendar entries
     */
    public record Query(
            CalConfig.EventType eventType,
            CalConfig config,
            Instant rangeStart,
            Instant rangeEnd,
            Instant now,
            List<Interval> busy) {
    }

    /** The range a booking keeps free around itself: the slot plus its buffers. */
    public static Interval blockRange(Instant start, CalConfig.EventType eventType) {
        return new Interval(
                start.minus(Duration.ofMinutes(eventType.bufferBeforeMinutes())),
                start.plus(Duration.ofMinutes((long) eventType.durationMinutes() + eventType.bufferAfterMinutes())));
    }

    /** Bookable slots sorted by start. */
    public static List<Interval> compute(Query query) {
        CalConfig.EventType eventType = query.eventType();
        ZoneId zone = query.config().timeZone();
        Duration duration = Duration.ofMinutes(eventType.durationMinutes());
        Duration step = Duration.ofMinutes(eventType.slotIntervalMinutes());

        Instant earliest = max(query.rangeStart(), query.now().plus(Duration.ofMinutes(eventType.minimumNoticeMinutes())));
        Instant latest = eventType.bookingWindowDays() == null
                ? query.rangeEnd()
                : min(query.rangeEnd(), query.now().plus(Duration.ofDays(eventType.bookingWindowDays())));
        if (earliest.isAfter(latest)) {
            return List.of();
        }

        TreeSet<Instant> starts = new TreeSet<>();
        // One day of margin on both sides: a local day can begin on the previous UTC date.
        LocalDate firstDay = earliest.minus(Duration.ofDays(1)).atZone(zone).toLocalDate();
        LocalDate lastDay = latest.plus(Duration.ofDays(1)).atZone(zone).toLocalDate();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            List<CalConfig.Window> windows = query.config().dateOverrides().get(day);
            if (windows == null) {
                windows = query.config().weekly().getOrDefault(day.getDayOfWeek(), List.of());
            }
            for (CalConfig.Window window : windows) {
                Optional<Instant> windowStart = toInstant(day, window.fromMinutes(), zone);
                Optional<Instant> windowEnd = toInstant(day, window.toMinutes(), zone);
                // A window edge inside a DST gap does not exist on that day; skip the window instead of guessing.
                if (windowStart.isEmpty() || windowEnd.isEmpty()) {
                    continue;
                }
                for (Instant start = windowStart.get(); !start.plus(duration).isAfter(windowEnd.get()); start = start.plus(step)) {
                    if (start.isBefore(earliest) || start.isAfter(latest)) {
                        continue;
                    }
                    Interval block = blockRange(start, eventType);
                    if (query.busy().stream().noneMatch(block::overlaps)) {
                        starts.add(start);
                    }
                }
            }
        }

        List<Interval> slots = new ArrayList<>(starts.size());
        for (Instant start : starts) {
            slots.add(new Interval(start, start.plus(duration)));
        }
        return slots;
    }

    /** True when {@code start} is exactly one of the currently bookable slot starts. */
    public static boolean isBookableStart(Instant start, CalConfig.EventType eventType, CalConfig config, Instant now,
            List<Interval> busy) {
        return compute(new Query(eventType, config, start, start, now, busy)).stream()
                .anyMatch(slot -> slot.start().equals(start));
    }

    /**
     * Instant of "minutes after local midnight" on a day. Empty inside a spring-forward gap; for the
     * repeated hour in autumn the earlier instant wins.
     */
    static Optional<Instant> toInstant(LocalDate day, int minutes, ZoneId zone) {
        LocalDateTime local = minutes >= 24 * 60
                ? day.plusDays(1).atStartOfDay().plusMinutes(minutes - 24 * 60)
                : day.atStartOfDay().plusMinutes(minutes);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        return offsets.isEmpty() ? Optional.empty() : Optional.of(local.toInstant(offsets.get(0)));
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
