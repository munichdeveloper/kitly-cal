package de.atstck.kitly.cal.google;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection to the host's Google Calendar (prefix {@code kitly-cal.google}). Switched on when a
 * refresh token is set. The token belongs to the host's own Google account and needs the scopes
 * {@code calendar.events} and {@code calendar.freebusy}; see README, section "Google Kalender".
 *
 * @param calendarId      calendar that receives confirmed bookings; "primary" = the account's main calendar
 * @param busyCalendarIds calendars whose appointments block slots; empty = only {@code calendarId}
 * @param sendUpdates     "all": Google e-mails the customer an invitation with the Meet link; "none": silent
 * @param cacheSeconds    how long a free/busy answer is reused for the same range
 */
@ConfigurationProperties(prefix = "kitly-cal.google")
public record GoogleProperties(
        String clientId,
        String clientSecret,
        String refreshToken,
        String calendarId,
        List<String> busyCalendarIds,
        String sendUpdates,
        Boolean createMeetLink,
        Integer cacheSeconds,
        Integer timeoutMs,
        String apiBaseUrl,
        String tokenUrl) {

    public boolean enabled() {
        return hasText(refreshToken);
    }

    public String calendar() {
        return hasText(calendarId) ? calendarId.trim() : "primary";
    }

    public List<String> busyCalendars() {
        List<String> ids = busyCalendarIds == null ? List.of()
                : busyCalendarIds.stream().filter(GoogleProperties::hasText).map(String::trim).distinct().toList();
        return ids.isEmpty() ? List.of(calendar()) : ids;
    }

    public String updates() {
        return hasText(sendUpdates) ? sendUpdates.trim() : "all";
    }

    public boolean meet() {
        return createMeetLink == null || createMeetLink;
    }

    public int cache() {
        return cacheSeconds == null ? 60 : Math.max(0, cacheSeconds);
    }

    public int timeout() {
        return timeoutMs == null ? 10000 : timeoutMs;
    }

    public String api() {
        return hasText(apiBaseUrl) ? apiBaseUrl.replaceAll("/+$", "") : "https://www.googleapis.com/calendar/v3";
    }

    public String token() {
        return hasText(tokenUrl) ? tokenUrl : "https://oauth2.googleapis.com/token";
    }

    /** Problems that keep the connection from working; empty when it is usable or switched off. */
    public List<String> problems() {
        if (!enabled()) {
            return List.of();
        }
        java.util.ArrayList<String> problems = new java.util.ArrayList<>();
        if (!hasText(clientId) || !hasText(clientSecret)) {
            problems.add("kitly-cal.google: client-id and client-secret are required when a refresh token is set.");
        }
        if (!List.of("all", "externalOnly", "none").contains(updates())) {
            problems.add("kitly-cal.google.send-updates must be all, externalOnly or none.");
        }
        return problems;
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
