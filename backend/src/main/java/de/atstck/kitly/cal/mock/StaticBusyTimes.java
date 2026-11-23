package de.atstck.kitly.cal.mock;

import de.atstck.kitly.cal.availability.BusyTimeProvider;
import de.atstck.kitly.cal.domain.Interval;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A hand-fed external calendar. {@code failWith} simulates an unreachable calendar. */
public class StaticBusyTimes implements BusyTimeProvider {

    private final List<Interval> intervals = new CopyOnWriteArrayList<>();
    private volatile Exception failWith;

    public void block(Instant start, Instant end) {
        intervals.add(new Interval(start, end));
    }

    @Override
    public List<Interval> getBusy(Interval range) throws Exception {
        if (failWith != null) {
            throw failWith;
        }
        return intervals.stream().filter(interval -> interval.overlaps(range)).toList();
    }

    public void failWith(Exception error) {
        failWith = error;
    }

    public void reset() {
        intervals.clear();
        failWith = null;
    }
}
