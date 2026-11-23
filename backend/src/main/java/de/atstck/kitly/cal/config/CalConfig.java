package de.atstck.kitly.cal.config;

import java.net.URI;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validated configuration with defaults filled in. Built once at startup; the service refuses to
 * start when {@link #from(CalProperties)} finds a problem.
 */
public record CalConfig(
        ZoneId timeZone,
        Organizer organizer,
        List<EventType> eventTypes,
        Map<DayOfWeek, List<Window>> weekly,
        Map<LocalDate, List<Window>> dateOverrides,
        FailureMode busyTimeFailureMode,
        Webhook webhook,
        List<String> liveKeys,
        List<String> sandboxKeys,
        StoreType store,
        DispatchMode dispatchMode) {

    public static final int MIN_KEY_LENGTH = 24;

    public record Organizer(String name, String email) {
    }

    /**
     * @param id                   sent by the app as {@code eventTypeId} (CALCOM_EVENT_TYPE_ID)
     * @param slotIntervalMinutes  distance between two slot starts
     * @param minimumNoticeMinutes a slot must start at least this long after "now"
     * @param bookingWindowDays    slots further in the future are not offered; null = unlimited
     * @param location             static meeting link or place, passed on in the webhook and the ICS file
     */
    public record EventType(
            int id,
            String slug,
            String title,
            int durationMinutes,
            int slotIntervalMinutes,
            int minimumNoticeMinutes,
            Integer bookingWindowDays,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            String location) {
    }

    /** Availability window in minutes after local midnight; {@code toMinutes} may be 1440 (24:00). */
    public record Window(int fromMinutes, int toMinutes) {
    }

    /**
     * @param url        receiver of BOOKING_CREATED for real bookings; null = send none
     * @param sandboxUrl receiver for sandbox bookings; null = sandbox bookings send nothing
     */
    public record Webhook(String url, String sandboxUrl, String secret, int timeoutMs, int maxAttempts) {

        public String urlFor(boolean sandbox) {
            return sandbox ? sandboxUrl : url;
        }
    }

    /** What to do when the external calendar cannot be read. */
    public enum FailureMode {
        /** Offer no slots. */
        CLOSED,
        /** Carry on with the service's own bookings only. */
        OPEN
    }

    public enum StoreType { JDBC, MEMORY }

    /** When effects owed by a confirmation are delivered. */
    public enum DispatchMode {
        /** Right away on a background thread, without delaying the response. */
        BACKGROUND,
        /** Before the confirm response is sent. Deterministic; used by tests. */
        INLINE,
        /** Only when the poller or POST /internal/outbox/run asks for it. */
        MANUAL
    }

    public Optional<EventType> eventType(int id) {
        return eventTypes.stream().filter(eventType -> eventType.id() == id).findFirst();
    }

    private static final Map<String, DayOfWeek> WEEKDAYS = Map.of(
            "mon", DayOfWeek.MONDAY, "tue", DayOfWeek.TUESDAY, "wed", DayOfWeek.WEDNESDAY, "thu", DayOfWeek.THURSDAY,
            "fri", DayOfWeek.FRIDAY, "sat", DayOfWeek.SATURDAY, "sun", DayOfWeek.SUNDAY);

    public static CalConfig from(CalProperties raw) {
        Set<String> problems = new LinkedHashSet<>();

        ZoneId zone = ZoneId.of("Europe/Berlin");
        if (hasText(raw.timeZone())) {
            try {
                zone = ZoneId.of(raw.timeZone());
            } catch (DateTimeException e) {
                problems.add("time-zone \"" + raw.timeZone() + "\" is not a valid IANA zone.");
            }
        }

        Organizer organizer = new Organizer(
                raw.organizer() == null ? "" : trim(raw.organizer().name()),
                raw.organizer() == null ? "" : trim(raw.organizer().email()));
        if (organizer.name().isEmpty() || organizer.email().isEmpty()) {
            problems.add("organizer.name and organizer.email are required.");
        }

        List<EventType> eventTypes = new ArrayList<>();
        List<CalProperties.EventType> rawEventTypes = raw.eventTypes() == null ? List.of() : raw.eventTypes();
        if (rawEventTypes.isEmpty()) {
            problems.add("At least one entry in event-types is required.");
        }
        for (int index = 0; index < rawEventTypes.size(); index++) {
            CalProperties.EventType item = rawEventTypes.get(index);
            String where = "event-types[" + index + "]";
            int id = orElse(item.id(), 0);
            int duration = orElse(item.durationMinutes(), 0);
            int interval = orElse(item.slotIntervalMinutes(), duration);
            int notice = orElse(item.minimumNoticeMinutes(), 0);
            int before = orElse(item.bufferBeforeMinutes(), 0);
            int after = orElse(item.bufferAfterMinutes(), 0);
            if (id <= 0) {
                problems.add(where + ".id must be a positive integer.");
            }
            if (eventTypes.stream().anyMatch(other -> other.id() == id)) {
                problems.add(where + ".id " + id + " is used twice.");
            }
            if (duration <= 0) {
                problems.add(where + ".duration-minutes must be a positive integer.");
            }
            if (interval <= 0) {
                problems.add(where + ".slot-interval-minutes must be a positive integer.");
            }
            if (notice < 0) {
                problems.add(where + ".minimum-notice-minutes must not be negative.");
            }
            if (item.bookingWindowDays() != null && item.bookingWindowDays() <= 0) {
                problems.add(where + ".booking-window-days must be a positive integer or absent.");
            }
            if (before < 0 || after < 0) {
                problems.add(where + ": buffers must not be negative.");
            }
            eventTypes.add(new EventType(
                    id,
                    hasText(item.slug()) ? item.slug() : "event-" + id,
                    hasText(item.title()) ? item.title() : "Event " + id,
                    duration, interval, notice, item.bookingWindowDays(), before, after,
                    hasText(item.location()) ? item.location() : null));
        }

        Map<DayOfWeek, List<Window>> weekly = new EnumMap<>(DayOfWeek.class);
        Map<String, List<CalProperties.TimeWindow>> rawWeekly =
                raw.availability() == null || raw.availability().weekly() == null ? Map.of() : raw.availability().weekly();
        for (Map.Entry<String, List<CalProperties.TimeWindow>> entry : rawWeekly.entrySet()) {
            DayOfWeek day = WEEKDAYS.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (day == null) {
                problems.add("availability.weekly." + entry.getKey() + " is not a weekday (mon, tue, wed, thu, fri, sat, sun).");
                continue;
            }
            weekly.put(day, parseWindows(entry.getValue(), "availability.weekly." + entry.getKey(), problems));
        }
        if (weekly.values().stream().allMatch(List::isEmpty)) {
            problems.add("availability.weekly has no bookable window at all.");
        }

        Map<LocalDate, List<Window>> overrides = new HashMap<>();
        List<CalProperties.DateOverride> rawOverrides =
                raw.availability() == null || raw.availability().dateOverrides() == null
                        ? List.of() : raw.availability().dateOverrides();
        for (CalProperties.DateOverride override : rawOverrides) {
            try {
                LocalDate date = LocalDate.parse(String.valueOf(override.date()));
                overrides.put(date, parseWindows(override.windows(), "availability.date-overrides " + date, problems));
            } catch (DateTimeException e) {
                problems.add("availability.date-overrides: \"" + override.date() + "\" is not a YYYY-MM-DD date.");
            }
        }

        FailureMode failureMode = FailureMode.CLOSED;
        if (hasText(raw.busyTimeFailureMode())) {
            try {
                failureMode = FailureMode.valueOf(raw.busyTimeFailureMode().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("busy-time-failure-mode must be \"closed\" or \"open\".");
            }
        }

        CalProperties.Webhook rawWebhook = raw.webhook();
        boolean allowInsecure = rawWebhook != null && Boolean.TRUE.equals(rawWebhook.allowInsecure());
        Webhook webhook = new Webhook(
                rawWebhook == null ? null : checkUrl(rawWebhook.url(), "webhook.url", allowInsecure, problems),
                rawWebhook == null ? null : checkUrl(rawWebhook.sandboxUrl(), "webhook.sandbox-url", allowInsecure, problems),
                rawWebhook == null || !hasText(rawWebhook.secret()) ? null : rawWebhook.secret().trim(),
                rawWebhook == null ? 10_000 : orElse(rawWebhook.timeoutMs(), 10_000),
                rawWebhook == null ? 6 : orElse(rawWebhook.maxAttempts(), 6));
        // The app rejects unsigned webhooks (fail closed), so an unsigned sender would only produce dead deliveries.
        if ((webhook.url() != null || webhook.sandboxUrl() != null) && webhook.secret() == null) {
            problems.add("A webhook URL is set but webhook.secret is missing.");
        }
        // A sandbox booking announced to the live receiver would trigger real customer mails there.
        if (webhook.url() != null && webhook.url().equals(webhook.sandboxUrl())) {
            problems.add("webhook.sandbox-url must differ from webhook.url.");
        }
        if (webhook.timeoutMs() <= 0 || webhook.maxAttempts() <= 0) {
            problems.add("webhook.timeout-ms and webhook.max-attempts must be positive integers.");
        }

        List<String> liveKeys = cleanKeys(raw.auth() == null ? null : raw.auth().apiKeys());
        List<String> sandboxKeys = cleanKeys(raw.auth() == null ? null : raw.auth().sandboxApiKeys());
        if (liveKeys.isEmpty() && sandboxKeys.isEmpty()) {
            problems.add("At least one API key is required (auth.api-keys or auth.sandbox-api-keys).");
        }
        for (String key : liveKeys) {
            if (key.length() < MIN_KEY_LENGTH) {
                problems.add("API keys need at least " + MIN_KEY_LENGTH + " characters.");
            }
            if (sandboxKeys.contains(key)) {
                problems.add("A key must not be both a live key and a sandbox key.");
            }
        }
        for (String key : sandboxKeys) {
            if (key.length() < MIN_KEY_LENGTH) {
                problems.add("API keys need at least " + MIN_KEY_LENGTH + " characters.");
            }
        }

        StoreType store = StoreType.JDBC;
        if (hasText(raw.store())) {
            try {
                store = StoreType.valueOf(raw.store().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("store must be \"jdbc\" or \"memory\".");
            }
        }

        DispatchMode dispatchMode = DispatchMode.BACKGROUND;
        if (raw.outbox() != null && hasText(raw.outbox().mode())) {
            try {
                dispatchMode = DispatchMode.valueOf(raw.outbox().mode().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("outbox.mode must be \"background\", \"inline\" or \"manual\".");
            }
        }

        if (!problems.isEmpty()) {
            throw new ConfigException(List.copyOf(problems));
        }
        return new CalConfig(zone, organizer, List.copyOf(eventTypes), Map.copyOf(weekly), Map.copyOf(overrides),
                failureMode, webhook, liveKeys, sandboxKeys, store, dispatchMode);
    }

    private static List<Window> parseWindows(List<CalProperties.TimeWindow> raw, String where, Set<String> problems) {
        List<Window> windows = new ArrayList<>();
        if (raw == null) {
            return windows;
        }
        for (CalProperties.TimeWindow item : raw) {
            Integer from = parseClock(item.from());
            Integer to = parseClock(item.to());
            if (from == null || to == null || to <= from) {
                problems.add(where + ": \"" + item.from() + "\"–\"" + item.to() + "\" is not a valid HH:MM window.");
                continue;
            }
            windows.add(new Window(from, to));
        }
        return List.copyOf(windows);
    }

    /** Parses "HH:MM" from 00:00 to 24:00 into minutes after midnight; null when malformed. */
    static Integer parseClock(String value) {
        if (value == null || value.length() != 5 || value.charAt(2) != ':') {
            return null;
        }
        try {
            int hours = Integer.parseInt(value.substring(0, 2));
            int minutes = Integer.parseInt(value.substring(3));
            int total = hours * 60 + minutes;
            return hours < 0 || minutes < 0 || minutes > 59 || total > 24 * 60 ? null : total;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "host.docker.internal");

    private static String checkUrl(String value, String name, boolean allowInsecure, Set<String> problems) {
        if (!hasText(value)) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
                problems.add(name + " is not a valid URL.");
                return null;
            }
            if (scheme.equals("http") && !allowInsecure && !LOCAL_HOSTS.contains(uri.getHost())) {
                problems.add(name + " must use https (http is only allowed for localhost and host.docker.internal).");
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            problems.add(name + " is not a valid URL.");
            return null;
        }
    }

    private static List<String> cleanKeys(List<String> keys) {
        if (keys == null) {
            return List.of();
        }
        return keys.stream().filter(CalConfig::hasText).map(String::trim).distinct().toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int orElse(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
