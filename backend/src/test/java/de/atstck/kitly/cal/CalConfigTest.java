package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.config.CalProperties;
import de.atstck.kitly.cal.config.ConfigException;
import de.atstck.kitly.cal.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalConfigTest {

    private static String problems(Fixtures.Props props) {
        try {
            props.build();
            return "";
        } catch (ConfigException e) {
            return String.join("\n", e.problems());
        }
    }

    @Test
    void fillsSensibleDefaults() {
        Fixtures.Props props = new Fixtures.Props();
        props.eventTypes = List.of(new CalProperties.EventType(7, null, null, 120, null, null, null, null, null, null));
        CalConfig config = props.build();
        assertThat(config.eventTypes().get(0)).isEqualTo(
                new CalConfig.EventType(7, "event-7", "Event 7", 120, 120, 0, null, 0, 0, null));
        assertThat(config.busyTimeFailureMode()).isEqualTo(CalConfig.FailureMode.CLOSED);
        assertThat(config.webhook().timeoutMs()).isEqualTo(10_000);
        assertThat(config.webhook().maxAttempts()).isEqualTo(6);
        assertThat(config.timeZone().getId()).isEqualTo("Europe/Berlin");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        Fixtures.Props props = new Fixtures.Props();
        props.timeZone = "Mars/Olympus";
        props.organizer = null;
        props.eventTypes = List.of(new CalProperties.EventType(0, null, null, -5, null, null, null, null, null, null));
        props.weekly.clear();
        props.weekly.put("funday", List.of());
        props.weekly.put("mon", List.of(new CalProperties.TimeWindow("17:00", "09:00")));
        props.overrides.add(new CalProperties.DateOverride("2026-13-01", List.of()));
        props.liveKeys = List.of("short");
        props.webhookUrl = "http://example.com/hook";
        props.webhookSecret = null;
        props.failureMode = "maybe";

        assertThatThrownBy(props::build).isInstanceOf(ConfigException.class).hasMessageContaining("Invalid kitly-cal configuration");
        String found = problems(props);
        assertThat(found).contains("time-zone", "organizer", "duration-minutes", "funday", "17:00", "2026-13-01",
                "at least 24 characters", "must use https", "webhook.secret is missing", "busy-time-failure-mode",
                "no bookable window");
    }

    @Test
    void requiresAKeyAndKeepsLiveAndSandboxKeysApart() {
        Fixtures.Props none = new Fixtures.Props();
        none.liveKeys = List.of();
        none.sandboxKeys = List.of("", "  ");
        assertThat(problems(none)).contains("At least one API key is required");

        Fixtures.Props shared = new Fixtures.Props();
        shared.sandboxKeys = shared.liveKeys;
        assertThat(problems(shared)).contains("both a live key and a sandbox key");
    }

    @Test
    void refusesToSendSandboxWebhooksToTheLiveReceiver() {
        Fixtures.Props same = new Fixtures.Props();
        same.sandboxWebhookUrl = same.webhookUrl;
        assertThat(problems(same)).contains("sandbox-url must differ");

        Fixtures.Props different = new Fixtures.Props();
        different.sandboxWebhookUrl = "https://app.test/api/test-hook";
        assertThat(problems(different)).isEmpty();
    }

    @Test
    void allowsHttpWebhooksOnlyForLocalTargets() {
        for (String url : List.of("http://localhost:3000/api/cal/webhook", "http://host.docker.internal:3000/api/cal/webhook")) {
            Fixtures.Props props = new Fixtures.Props();
            props.webhookUrl = url;
            assertThat(problems(props)).isEmpty();
        }
    }

    @Test
    void needsNoWebhookAtAll() {
        Fixtures.Props props = new Fixtures.Props();
        props.webhookUrl = "";
        props.webhookSecret = "";
        CalConfig config = props.build();
        assertThat(config.webhook().url()).isNull();
        assertThat(config.webhook().urlFor(true)).isNull();
    }
}
