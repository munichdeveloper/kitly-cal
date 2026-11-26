package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.domain.Attendee;
import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.BookingStatus;
import de.atstck.kitly.cal.domain.Interval;
import de.atstck.kitly.cal.google.GoogleBusyTimes;
import de.atstck.kitly.cal.google.GoogleCalendarWriter;
import de.atstck.kitly.cal.google.GoogleClient;
import de.atstck.kitly.cal.google.GoogleProperties;
import de.atstck.kitly.cal.mock.MutableClock;
import de.atstck.kitly.cal.outbox.ConfirmationContext;
import de.atstck.kitly.cal.outbox.OutboxDispatcher.PermanentDeliveryException;
import de.atstck.kitly.cal.support.Fixtures;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Google adapters against a local stand-in for the token endpoint and the Calendar API. */
class GoogleCalendarTest {

    private record Call(String method, String path, String auth, String body) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant START = Instant.parse("2026-12-01T09:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T11:00:00Z");

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokens = new AtomicInteger();
    private final AtomicReference<String> freeBusyAnswer = new AtomicReference<>();
    private final AtomicInteger eventsStatus = new AtomicInteger(200);
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void start() throws Exception {
        clock.setFixed(Instant.parse("2026-11-24T08:00:00Z"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().toString();
            calls.add(new Call(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization"), body));
            int status = 200;
            String answer = "{}";
            if (path.startsWith("/token")) {
                answer = "{\"access_token\":\"at-" + tokens.incrementAndGet() + "\",\"expires_in\":3600}";
            } else if (path.startsWith("/api/freeBusy")) {
                answer = freeBusyAnswer.get();
            } else if (path.startsWith("/api/calendars/")) {
                status = eventsStatus.get();
                answer = status == 200 ? "{\"id\":\"x\"}" : "{\"error\":{\"code\":" + status + "}}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GoogleProperties props(List<String> busyIds, Integer cacheSeconds) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GoogleProperties("client", "secret", "refresh", "primary", busyIds, null, null, cacheSeconds, 2000,
                base + "/api", base + "/token");
    }

    private ConfirmationContext context() {
        CalConfig config = Fixtures.config();
        Booking booking = new Booking(1, "uid-1", false, 1, BookingStatus.ACCEPTED, START, END, START, END,
                new Attendee("Erika Beispiel", "erika@example.com", "Europe/Berlin", "de"), Map.of(), null,
                START, START, START, null);
        return new ConfirmationContext(booking, config.eventTypes().get(0), config.organizer());
    }

    @Test
    void readsBusyTimesOfEveryConfiguredCalendarAndCachesThem() throws Exception {
        freeBusyAnswer.set("{\"calendars\":{"
                + "\"primary\":{\"busy\":[{\"start\":\"2026-12-01T08:00:00Z\",\"end\":\"2026-12-01T09:30:00Z\"}]},"
                + "\"privat@example.com\":{\"busy\":[{\"start\":\"2026-12-02T10:00:00Z\",\"end\":\"2026-12-02T12:00:00Z\"}]}}}");
        GoogleProperties properties = props(List.of("primary", "privat@example.com"), 60);
        GoogleBusyTimes busyTimes = new GoogleBusyTimes(new GoogleClient(properties, MAPPER, clock), properties, clock);
        Interval range = new Interval(Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-12-08T00:00:00Z"));

        List<Interval> busy = busyTimes.getBusy(range);
        busyTimes.getBusy(range);

        assertThat(busy).containsExactly(
                new Interval(Instant.parse("2026-12-01T08:00:00Z"), Instant.parse("2026-12-01T09:30:00Z")),
                new Interval(Instant.parse("2026-12-02T10:00:00Z"), Instant.parse("2026-12-02T12:00:00Z")));
        List<Call> freeBusy = calls.stream().filter(call -> call.path().startsWith("/api/freeBusy")).toList();
        assertThat(freeBusy).hasSize(1);
        assertThat(freeBusy.get(0).auth()).isEqualTo("Bearer at-1");
        Map<?, ?> request = MAPPER.readValue(freeBusy.get(0).body(), Map.class);
        assertThat(request.get("timeMin")).isEqualTo("2026-12-01T00:00:00Z");
        assertThat(request.get("items")).isEqualTo(List.of(Map.of("id", "primary"), Map.of("id", "privat@example.com")));

        clock.advance(java.time.Duration.ofSeconds(61));
        busyTimes.getBusy(range);
        assertThat(calls.stream().filter(call -> call.path().startsWith("/api/freeBusy"))).hasSize(2);
        // The access token is reused within its lifetime.
        assertThat(tokens.get()).isEqualTo(1);
    }

    @Test
    void anUnreadableCalendarIsAnErrorNotAFreeCalendar() {
        freeBusyAnswer.set("{\"calendars\":{\"primary\":{\"errors\":[{\"domain\":\"global\",\"reason\":\"notFound\"}],\"busy\":[]}}}");
        GoogleProperties properties = props(null, 0);
        GoogleBusyTimes busyTimes = new GoogleBusyTimes(new GoogleClient(properties, MAPPER, clock), properties, clock);

        assertThatThrownBy(() -> busyTimes.getBusy(new Interval(START, END))).hasMessageContaining("notFound");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsTheEventWithGuestAndMeetLinkAndLetsGoogleSendTheInvitation() throws Exception {
        GoogleProperties properties = props(null, 60);
        new GoogleCalendarWriter(new GoogleClient(properties, MAPPER, clock), properties, null).createEvent(context());

        Call call = calls.stream().filter(item -> item.path().startsWith("/api/calendars/")).findFirst().orElseThrow();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/api/calendars/primary/events?sendUpdates=all&conferenceDataVersion=1");
        Map<String, Object> event = MAPPER.readValue(call.body(), Map.class);
        assertThat((String) event.get("id")).matches("[0-9a-v]{5,1024}");
        assertThat(event.get("start")).isEqualTo(Map.of("dateTime", "2026-12-01T09:00:00Z"));
        assertThat(event.get("end")).isEqualTo(Map.of("dateTime", "2026-12-01T11:00:00Z"));
        assertThat(event.get("attendees")).isEqualTo(List.of(Map.of("email", "erika@example.com", "displayName", "Erika Beispiel")));
        Map<String, Object> create = (Map<String, Object>) ((Map<String, Object>) event.get("conferenceData")).get("createRequest");
        assertThat(create.get("requestId")).isEqualTo("uid-1");
        assertThat(create.get("conferenceSolutionKey")).isEqualTo(Map.of("type", "hangoutsMeet"));
    }

    @Test
    void aRetryAfterALostAnswerDoesNotCreateASecondEvent() throws Exception {
        eventsStatus.set(409);
        GoogleProperties properties = props(null, 60);
        new GoogleCalendarWriter(new GoogleClient(properties, MAPPER, clock), properties, null).createEvent(context());
    }

    @Test
    void rejectedRequestsArePermanentButRateLimitsAreRetried() {
        GoogleProperties properties = props(null, 60);
        GoogleCalendarWriter writer = new GoogleCalendarWriter(new GoogleClient(properties, MAPPER, clock), properties, null);

        eventsStatus.set(400);
        assertThatThrownBy(() -> writer.createEvent(context())).isInstanceOf(PermanentDeliveryException.class);
        eventsStatus.set(429);
        assertThatThrownBy(() -> writer.createEvent(context())).isInstanceOf(GoogleClient.GoogleApiException.class);
    }

    @Test
    void fetchesAFreshTokenOnceWhenGoogleRejectsTheCurrentOne() throws Exception {
        eventsStatus.set(401);
        GoogleProperties properties = props(null, 60);
        GoogleCalendarWriter writer = new GoogleCalendarWriter(new GoogleClient(properties, MAPPER, clock), properties, null);

        assertThatThrownBy(() -> writer.createEvent(context())).isInstanceOf(GoogleClient.GoogleApiException.class);
        assertThat(tokens.get()).isEqualTo(2);
        assertThat(calls.stream().filter(call -> call.path().startsWith("/api/calendars/"))).hasSize(2);
    }

    @Test
    void refusesToStartWithoutClientCredentials() {
        GoogleProperties properties = new GoogleProperties(null, null, "refresh", null, null, "sometimes", null, null,
                null, null, null);
        assertThat(properties.problems()).hasSize(2);
        assertThat(new GoogleProperties(null, null, "", null, null, null, null, null, null, null, null).enabled()).isFalse();
    }
}
