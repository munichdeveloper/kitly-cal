package de.atstck.kitly.cal.mock;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.config.UidGenerator;
import de.atstck.kitly.cal.outbox.HttpWebhookSender;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Profile {@code mock}: the complete service with every outside dependency replaced by a recording
 * fake. No database, no e-mail, no calendar. Nothing that happens in this profile is a real booking.
 */
@Configuration
@Profile("mock")
public class MockConfig {

    @Bean
    MutableClock clock() {
        return new MutableClock();
    }

    /** Records webhooks; with kitly-cal.mock.forward-webhooks=true it also really sends them. */
    @Bean
    RecordingWebhookSender webhookSender(CalConfig config,
            @Value("${kitly-cal.mock.forward-webhooks:false}") boolean forward) {
        return forward
                ? new RecordingWebhookSender(new HttpWebhookSender(Duration.ofMillis(config.webhook().timeoutMs())))
                : new RecordingWebhookSender();
    }

    @Bean
    RecordingNotifier notifier() {
        return new RecordingNotifier();
    }

    @Bean
    RecordingCalendar calendar() {
        return new RecordingCalendar();
    }

    @Bean
    StaticBusyTimes busyTimes() {
        return new StaticBusyTimes();
    }

    @Bean
    SequentialUids uidGenerator() {
        return new SequentialUids();
    }

    /** Readable uids: mock-1, mock-2, ... */
    public static class SequentialUids implements UidGenerator {

        private final AtomicLong next = new AtomicLong(1);

        @Override
        public String next() {
            return "mock-" + next.getAndIncrement();
        }

        public void reset() {
            next.set(1);
        }
    }
}
