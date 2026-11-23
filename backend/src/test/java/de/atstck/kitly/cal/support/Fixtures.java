package de.atstck.kitly.cal.support;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.config.CalProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test configuration: Monday to Friday 09:00-17:00 Berlin, one event type (id 1) of two hours. */
public final class Fixtures {

    public static final String LIVE_KEY = "kcal_live_test_0123456789abcdef";
    public static final String SANDBOX_KEY = "kcal_test_test_0123456789abcdef";
    public static final String WEBHOOK_URL = "https://app.test/api/cal/webhook";
    public static final String WEBHOOK_SECRET = "test-webhook-secret";

    private Fixtures() {
    }

    /** A builder for raw properties, so a test can bend exactly one thing. */
    public static final class Props {

        public String timeZone = "Europe/Berlin";
        public CalProperties.Organizer organizer = new CalProperties.Organizer("Test Host", "host@example.com");
        public List<CalProperties.EventType> eventTypes = new ArrayList<>(List.of(Fixtures.eventType(120, null, 0, null, 0, 0)));
        public Map<String, List<CalProperties.TimeWindow>> weekly = new LinkedHashMap<>();
        public List<CalProperties.DateOverride> overrides = new ArrayList<>();
        public String failureMode;
        public String webhookUrl = WEBHOOK_URL;
        public String sandboxWebhookUrl;
        public String webhookSecret = WEBHOOK_SECRET;
        public Integer maxAttempts;
        public List<String> liveKeys = List.of(LIVE_KEY);
        public List<String> sandboxKeys = List.of(SANDBOX_KEY);
        public String outboxMode = "inline";

        public Props() {
            for (String day : List.of("mon", "tue", "wed", "thu", "fri")) {
                weekly.put(day, List.of(new CalProperties.TimeWindow("09:00", "17:00")));
            }
        }

        public Props eventType(CalProperties.EventType eventType) {
            eventTypes = new ArrayList<>(List.of(eventType));
            return this;
        }

        public CalProperties raw() {
            return new CalProperties(timeZone, organizer, eventTypes,
                    new CalProperties.Availability(weekly, overrides), failureMode,
                    new CalProperties.Webhook(webhookUrl, sandboxWebhookUrl, webhookSecret, null, maxAttempts, null),
                    new CalProperties.Auth(liveKeys, sandboxKeys), "memory",
                    new CalProperties.Outbox(outboxMode, false));
        }

        public CalConfig build() {
            return CalConfig.from(raw());
        }
    }

    public static CalProperties.EventType eventType(int duration, Integer interval, int notice, Integer windowDays,
            int bufferBefore, int bufferAfter) {
        return new CalProperties.EventType(1, "potenzialanalyse", "Potenzialanalyse", duration, interval, notice,
                windowDays, bufferBefore, bufferAfter, "https://meet.example.com/room");
    }

    public static CalConfig config() {
        return new Props().build();
    }
}
