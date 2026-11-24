package de.atstck.kitly.cal.config;

import de.atstck.kitly.cal.availability.BusyTimeProvider;
import de.atstck.kitly.cal.outbox.CalendarWriter;
import de.atstck.kitly.cal.outbox.HttpWebhookSender;
import de.atstck.kitly.cal.outbox.Notifier;
import de.atstck.kitly.cal.outbox.OutboxDispatcher;
import de.atstck.kitly.cal.outbox.WebhookSender;
import de.atstck.kitly.cal.service.BookingService;
import de.atstck.kitly.cal.store.BookingStore;
import de.atstck.kitly.cal.store.InMemoryBookingStore;
import de.atstck.kitly.cal.store.JdbcBookingStore;
import de.atstck.kitly.cal.web.ApiKeyFilter;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Wires the service from its parts. The single place where adapters are chosen. */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /** Validates the configuration; the application does not start when it is unusable. */
    @Bean
    CalConfig calConfig(CalProperties properties) {
        return CalConfig.from(properties);
    }

    @Bean
    JsonWriter jsonWriter(ObjectMapper mapper) {
        return mapper::writeValueAsString;
    }

    @Bean
    @Profile("!mock")
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "kitly-cal.store", havingValue = "jdbc", matchIfMissing = true)
    BookingStore jdbcBookingStore(JdbcClient jdbc, PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        return new JdbcBookingStore(jdbc, new TransactionTemplate(transactionManager), mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "kitly-cal.store", havingValue = "memory")
    InMemoryBookingStore inMemoryBookingStore() {
        log.warn("Using the in-memory store: every restart forgets all bookings. Never use this in production.");
        return new InMemoryBookingStore();
    }

    @Bean
    @Profile("!mock")
    WebhookSender webhookSender(CalConfig config) {
        return new HttpWebhookSender(Duration.ofMillis(config.webhook().timeoutMs()));
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService outboxExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    OutboxDispatcher outboxDispatcher(CalConfig config, BookingStore store, Clock clock, WebhookSender webhookSender,
            ObjectProvider<Notifier> notifier, ObjectProvider<CalendarWriter> calendar, ExecutorService outboxExecutor) {
        return new OutboxDispatcher(config, store, clock, webhookSender, notifier.getIfAvailable(),
                calendar.getIfAvailable(), outboxExecutor);
    }

    @Bean
    BookingService bookingService(CalConfig config, BookingStore store, Clock clock,
            ObjectProvider<BusyTimeProvider> busyTimes, OutboxDispatcher dispatcher, JsonWriter json,
            ObjectProvider<UidGenerator> uids) {
        return new BookingService(config, store, clock, busyTimes.getIfAvailable(), dispatcher, json, uids.getIfAvailable());
    }

    @Bean
    ApiKeyFilter apiKeyFilter(CalConfig config, Clock clock, JsonWriter json) {
        return new ApiKeyFilter(config, clock, json);
    }

    /** Say on every start which effects are not connected, instead of failing silently later. */
    @Bean
    ApplicationRunner startupWarnings(CalConfig config, OutboxDispatcher dispatcher, ObjectProvider<BusyTimeProvider> busyTimes) {
        return args -> {
            if (busyTimes.getIfAvailable() == null) {
                log.warn("No external calendar connected: private appointments of the host do not block slots.");
            }
            if (!dispatcher.hasNotifier() && !dispatcher.hasCalendar()) {
                log.warn("Neither notifier nor calendar connected: the customer gets no confirmation and no invitation.");
            }
            if (!dispatcher.hasCalendar()) {
                log.warn("No calendar writer connected: confirmed bookings are not written to the host's calendar.");
            }
            if (config.webhook().url() == null) {
                log.warn("No webhook URL configured: confirmed bookings trigger no BOOKING_CREATED.");
            }
            if (config.liveKeys().isEmpty()) {
                log.warn("No live API key configured: only sandbox bookings are possible.");
            }
        };
    }
}
