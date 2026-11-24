package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;

import de.atstck.kitly.cal.google.GoogleBusyTimes;
import de.atstck.kitly.cal.google.GoogleCalendarWriter;
import de.atstck.kitly.cal.google.GoogleConfig;
import de.atstck.kitly.cal.google.GoogleProperties;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Google is connected exactly when a refresh token is configured, and never half-configured. */
class GoogleConfigTest {

    @Configuration
    @EnableConfigurationProperties(GoogleProperties.class)
    static class Base {

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Base.class, GoogleConfig.class);

    @Test
    void staysOffWithoutRefreshToken() {
        runner.withPropertyValues("kitly-cal.google.refresh-token=")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(GoogleBusyTimes.class).doesNotHaveBean(GoogleCalendarWriter.class));
    }

    @Test
    void connectsBusyTimesAndCalendarWithRefreshToken() {
        runner.withPropertyValues("kitly-cal.google.refresh-token=r", "kitly-cal.google.client-id=c",
                        "kitly-cal.google.client-secret=s", "kitly-cal.google.busy-calendar-ids=primary,privat@example.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(GoogleBusyTimes.class).hasSingleBean(GoogleCalendarWriter.class);
                    assertThat(context.getBean(GoogleProperties.class).busyCalendars())
                            .containsExactly("primary", "privat@example.com");
                });
    }

    @Test
    void refusesToStartWhenClientCredentialsAreMissing() {
        runner.withPropertyValues("kitly-cal.google.refresh-token=r")
                .run(context -> assertThat(context).hasFailed());
    }
}
