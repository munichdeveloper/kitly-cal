package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.atstck.kitly.cal.outbox.Wire;
import de.atstck.kitly.cal.support.Fixtures;
import de.atstck.kitly.cal.support.Harness;
import de.atstck.kitly.cal.support.Http;
import de.atstck.kitly.cal.support.TestDatabase;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The application as it runs in production (default profile, real clock, PostgreSQL, real HTTP
 * webhook) with a local server standing in for the app's webhook route. Skipped without a database.
 */
@EnabledIf("de.atstck.kitly.cal.support.TestDatabase#available")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "kitly-cal.outbox.mode=manual",
        "kitly-cal.outbox.polling=false",
        "kitly-cal.time-zone=Europe/Berlin",
        "kitly-cal.organizer.name=Test Host",
        "kitly-cal.organizer.email=host@example.com",
        "kitly-cal.event-types[0].id=1",
        "kitly-cal.event-types[0].title=Potenzialanalyse",
        "kitly-cal.event-types[0].duration-minutes=60",
        // Bookable around the clock, so the test finds a slot whenever it runs.
        "kitly-cal.availability.weekly.mon[0].from=00:00", "kitly-cal.availability.weekly.mon[0].to=24:00",
        "kitly-cal.availability.weekly.tue[0].from=00:00", "kitly-cal.availability.weekly.tue[0].to=24:00",
        "kitly-cal.availability.weekly.wed[0].from=00:00", "kitly-cal.availability.weekly.wed[0].to=24:00",
        "kitly-cal.availability.weekly.thu[0].from=00:00", "kitly-cal.availability.weekly.thu[0].to=24:00",
        "kitly-cal.availability.weekly.fri[0].from=00:00", "kitly-cal.availability.weekly.fri[0].to=24:00",
        "kitly-cal.availability.weekly.sat[0].from=00:00", "kitly-cal.availability.weekly.sat[0].to=24:00",
        "kitly-cal.availability.weekly.sun[0].from=00:00", "kitly-cal.availability.weekly.sun[0].to=24:00",
        "kitly-cal.auth.api-keys=" + Fixtures.LIVE_KEY,
        "kitly-cal.auth.sandbox-api-keys=" + Fixtures.SANDBOX_KEY,
        "kitly-cal.webhook.secret=" + Fixtures.WEBHOOK_SECRET,
})
class PostgresIntegrationTest {

    private record Delivery(String signature, String deliveryId, String body) {
    }

    private static final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private static final HttpServer receiver = startReceiver();

    private static HttpServer startReceiver() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/cal/webhook", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                deliveries.add(new Delivery(exchange.getRequestHeaders().getFirst("x-cal-signature-256"),
                        exchange.getRequestHeaders().getFirst("x-kitly-cal-delivery-id"), body));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.Coordinates database = TestDatabase.require();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::user);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.hikari.schema", () -> TestDatabase.SCHEMA);
        registry.add("spring.flyway.default-schema", () -> TestDatabase.SCHEMA);
        registry.add("spring.flyway.schemas", () -> TestDatabase.SCHEMA);
        registry.add("kitly-cal.webhook.url", () -> "http://127.0.0.1:" + receiver.getAddress().getPort() + "/api/cal/webhook");
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop(0);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;

    private Http http;

    @BeforeEach
    void startOver() {
        http = new Http(port, Fixtures.LIVE_KEY);
        jdbc.sql("truncate outbox, bookings restart identity cascade").update();
        deliveries.clear();
    }

    /** The first slot offered in the next three days, as a UTC instant string. */
    private String firstSlot(String key) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Http.Result slots = http.get("/v2/slots?eventTypeId=1&start=" + today + "&end=" + today.plusDays(3) + "&timeZone=Europe/Berlin", key);
        assertThat(slots.status()).isEqualTo(200);
        String withOffset = slots.<Map<String, List<Map<String, String>>>>data().values().iterator().next().get(0).get("start");
        return OffsetDateTime.parse(withOffset).toInstant().toString();
    }

    @Test
    void runsReserveRaceConfirmAndWebhookThroughTheRealDatabase() throws Exception {
        assertThat(http.get("/health", null).body()).isEqualTo(Map.of("status", "ok"));
        String start = firstSlot(Fixtures.LIVE_KEY);

        // Twelve visitors click the same slot at the same moment.
        List<Callable<Http.Result>> clicks = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            clicks.add(() -> new Http(port, Fixtures.LIVE_KEY).post("/v2/bookings", Harness.bookingBody(start)));
        }
        List<Http.Result> results = StoreContract.inParallel(clicks).stream().map(Http.Result.class::cast).toList();
        assertThat(results.stream().filter(result -> result.status() == 201)).hasSize(1);
        assertThat(results.stream().filter(result -> result.status() == 400)).hasSize(11);
        Map<String, Object> booking = results.stream().filter(result -> result.status() == 201).findFirst().orElseThrow().data();
        String uid = (String) booking.get("uid");
        assertThat(uid).hasSize(22);
        assertThat(booking.get("metadata")).isEqualTo(Map.of("source", "dsb-potenzialanalyse", "intakeHash", "hash_1", "tenantId", "tenant_1"));
        assertThat(firstSlot(Fixtures.LIVE_KEY)).isNotEqualTo(start);
        assertThat(jdbc.sql("select count(*) from bookings").query(Integer.class).single()).isEqualTo(1);

        // The payment page is loaded twice at the same moment.
        List<Object> confirms = StoreContract.inParallel(List.<Callable<Http.Result>>of(
                () -> new Http(port, Fixtures.LIVE_KEY).post("/v2/bookings/" + uid + "/confirm", Map.of()),
                () -> new Http(port, Fixtures.LIVE_KEY).post("/v2/bookings/" + uid + "/confirm", Map.of())));
        assertThat(confirms).allSatisfy(result -> assertThat(((Http.Result) result).status()).isEqualTo(200));
        assertThat(jdbc.sql("select count(*) from outbox").query(Integer.class).single()).isEqualTo(1);
        assertThat(deliveries).isEmpty(); // manual mode: nothing was sent yet

        assertThat(http.post("/internal/outbox/run", null).<Map<String, Object>>data())
                .isEqualTo(Map.of("delivered", 1, "retrying", 0, "dead", 0));
        assertThat(deliveries).hasSize(1);
        Delivery delivery = deliveries.get(0);
        assertThat(delivery.signature()).isEqualTo(Wire.sign(delivery.body(), Fixtures.WEBHOOK_SECRET));
        assertThat(delivery.deliveryId()).isEqualTo("1");
        assertThat(delivery.body()).contains("\"triggerEvent\":\"BOOKING_CREATED\"", "\"uid\":\"" + uid + "\"",
                "\"tenantId\":\"tenant_1\"", "\"email\":\"erika@example.com\"");
        // A second run finds nothing left to do.
        assertThat(http.post("/internal/outbox/run", null).<Map<String, Object>>data())
                .isEqualTo(Map.of("delivered", 0, "retrying", 0, "dead", 0));

        assertThat(http.post("/v2/bookings/" + uid + "/decline", Map.of("reason", "expired")).status()).isEqualTo(400);
        assertThat(jdbc.sql("select status from bookings where uid = :uid").param("uid", uid).query(String.class).single())
                .isEqualTo("accepted");
        assertThat(http.get("/v2/bookings?status=unconfirmed").<List<Object>>data()).isEmpty();
        assertThat(http.get("/v2/bookings?status=upcoming").<List<Object>>data()).hasSize(1);
    }

    @Test
    void letsTheHousekeepingCallsReleaseAnUnpaidReservation() {
        String start = firstSlot(Fixtures.LIVE_KEY);
        String uid = (String) http.post("/v2/bookings", Harness.bookingBody(start)).<Map<String, Object>>data().get("uid");

        List<Map<String, Object>> pending = http.get("/v2/bookings?status=unconfirmed&take=100&skip=0").data();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0)).containsEntry("uid", uid).containsKey("createdAt");

        assertThat(http.post("/v2/bookings/" + uid + "/decline", Map.of("reason", "Zahlung nicht abgeschlossen")).status()).isEqualTo(200);
        assertThat(firstSlot(Fixtures.LIVE_KEY)).isEqualTo(start);
        assertThat(http.post("/v2/bookings/" + uid + "/confirm", Map.of()).status()).isEqualTo(400);
        assertThat(jdbc.sql("select rejection_reason from bookings where uid = :uid").param("uid", uid).query(String.class).single())
                .isEqualTo("Zahlung nicht abgeschlossen");
        assertThat(deliveries).isEmpty();
    }

    @Test
    void keepsSandboxBookingsOutOfTheRealWorldInTheDatabaseToo() {
        String start = firstSlot(Fixtures.SANDBOX_KEY);
        Http.Result test = http.post("/v2/bookings", Harness.bookingBody(start), Fixtures.SANDBOX_KEY);
        assertThat(test.status()).isEqualTo(201);
        String uid = (String) test.<Map<String, Object>>data().get("uid");

        // Same slot, real key: still free.
        assertThat(http.post("/v2/bookings", Harness.bookingBody(start)).status()).isEqualTo(201);
        assertThat(http.post("/v2/bookings/" + uid + "/confirm", Map.of(), Fixtures.SANDBOX_KEY).status()).isEqualTo(200);
        assertThat(http.post("/internal/outbox/run", null).<Map<String, Object>>data())
                .isEqualTo(Map.of("delivered", 0, "retrying", 0, "dead", 0));
        assertThat(deliveries).isEmpty();
        assertThat(jdbc.sql("select count(*) from bookings where sandbox").query(Integer.class).single()).isEqualTo(1);
    }
}
