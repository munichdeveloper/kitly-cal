package de.atstck.kitly.cal.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Raw configuration as written in YAML and environment variables (prefix {@code kitly-cal}).
 * Nothing here is checked; {@link CalConfig#from(CalProperties)} validates it and fills defaults.
 */
@ConfigurationProperties(prefix = "kitly-cal")
public record CalProperties(
        String timeZone,
        Organizer organizer,
        List<EventType> eventTypes,
        Availability availability,
        String busyTimeFailureMode,
        Webhook webhook,
        Auth auth,
        String store,
        Outbox outbox) {

    public record Organizer(String name, String email) {
    }

    public record EventType(
            Integer id,
            String slug,
            String title,
            Integer durationMinutes,
            Integer slotIntervalMinutes,
            Integer minimumNoticeMinutes,
            Integer bookingWindowDays,
            Integer bufferBeforeMinutes,
            Integer bufferAfterMinutes,
            String location) {
    }

    public record TimeWindow(String from, String to) {
    }

    public record DateOverride(String date, List<TimeWindow> windows) {
    }

    public record Availability(Map<String, List<TimeWindow>> weekly, List<DateOverride> dateOverrides) {
    }

    public record Webhook(
            String url,
            String sandboxUrl,
            String secret,
            Integer timeoutMs,
            Integer maxAttempts,
            Boolean allowInsecure) {
    }

    public record Auth(List<String> apiKeys, List<String> sandboxApiKeys) {
    }

    public record Outbox(String mode, Boolean polling) {
    }
}
