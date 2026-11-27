package de.atstck.kitly.cal.google;

import de.atstck.kitly.cal.config.ConfigException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

/**
 * Connects Google Calendar when {@code kitly-cal.google.refresh-token} is set: busy times block slots
 * and confirmed bookings become calendar events with a Meet link. Never in the mock profile.
 */
@Configuration
@Profile("!mock")
@ConditionalOnExpression("!'${kitly-cal.google.refresh-token:}'.isBlank()")
public class GoogleConfig {

    private static final Logger log = LoggerFactory.getLogger(GoogleConfig.class);

    @Bean
    GoogleClient googleClient(GoogleProperties properties, ObjectMapper mapper, Clock clock) {
        List<String> problems = properties.problems();
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        log.info("Google Calendar connected: busy calendars={}, send-updates={}, meet={}, sandbox calendar={}",
                properties.busyCalendars().size(), properties.updates(), properties.meet(),
                properties.sandboxCalendarEnabled() ? "on, send-updates=" + properties.sandboxUpdates() : "off");
        return new GoogleClient(properties, mapper, clock);
    }

    @Bean
    GoogleBusyTimes googleBusyTimes(GoogleClient client, GoogleProperties properties, Clock clock) {
        return new GoogleBusyTimes(client, properties, clock);
    }

    @Bean
    GoogleCalendarWriter googleCalendarWriter(GoogleClient client, GoogleProperties properties, GoogleBusyTimes busyTimes) {
        return new GoogleCalendarWriter(client, properties, busyTimes);
    }
}
