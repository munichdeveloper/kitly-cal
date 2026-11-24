package de.atstck.kitly.cal.google;

import de.atstck.kitly.cal.availability.BusyTimeProvider;
import de.atstck.kitly.cal.domain.Interval;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads busy times from the host's Google calendars (FreeBusy API). Every appointment there blocks
 * slots, including ones Kitly-cal itself created. Answers are cached briefly per range because the
 * public slot page of the app asks often (RISIKEN.md R4).
 */
public class GoogleBusyTimes implements BusyTimeProvider {

    private static final int MAX_CACHE_ENTRIES = 64;

    private record Cached(Instant until, List<Interval> busy) {
    }

    private final GoogleClient client;
    private final GoogleProperties properties;
    private final Clock clock;
    private final Map<Interval, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Interval, Cached> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    public GoogleBusyTimes(GoogleClient client, GoogleProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<Interval> getBusy(Interval range) throws Exception {
        Instant now = clock.instant();
        synchronized (cache) {
            Cached hit = cache.get(range);
            if (hit != null && now.isBefore(hit.until())) {
                return hit.busy();
            }
        }
        List<Interval> busy = fetch(range);
        if (properties.cache() > 0) {
            synchronized (cache) {
                cache.put(range, new Cached(now.plusSeconds(properties.cache()), busy));
            }
        }
        return busy;
    }

    /** Drops cached answers, e.g. after a booking was written to the calendar. */
    public void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Interval> fetch(Interval range) throws Exception {
        List<Map<String, String>> items = properties.busyCalendars().stream().map(id -> Map.of("id", id)).toList();
        Map<String, Object> answer = client.send("POST", "/freeBusy", Map.of(
                "timeMin", range.start().toString(),
                "timeMax", range.end().toString(),
                "items", items));

        Map<String, Object> calendars = (Map<String, Object>) answer.getOrDefault("calendars", Map.of());
        List<Interval> busy = new ArrayList<>();
        for (String id : properties.busyCalendars()) {
            Map<String, Object> calendar = (Map<String, Object>) calendars.get(id);
            if (calendar == null) {
                throw new IllegalStateException("Google FreeBusy returned nothing for a configured calendar.");
            }
            // An unreadable calendar must not look free: fail and let busy-time-failure-mode decide.
            List<Map<String, Object>> errors = (List<Map<String, Object>>) calendar.get("errors");
            if (errors != null && !errors.isEmpty()) {
                throw new IllegalStateException("Google FreeBusy cannot read a configured calendar: "
                        + errors.get(0).get("reason"));
            }
            for (Map<String, Object> period : (List<Map<String, Object>>) calendar.getOrDefault("busy", List.of())) {
                Instant start = Instant.parse(String.valueOf(period.get("start")));
                Instant end = Instant.parse(String.valueOf(period.get("end")));
                if (start.isBefore(end)) {
                    busy.add(new Interval(start, end));
                }
            }
        }
        return List.copyOf(busy);
    }
}
