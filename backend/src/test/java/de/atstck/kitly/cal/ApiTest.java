package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;

import de.atstck.kitly.cal.mock.MutableClock;
import de.atstck.kitly.cal.mock.RecordingCalendar;
import de.atstck.kitly.cal.mock.RecordingNotifier;
import de.atstck.kitly.cal.mock.RecordingWebhookSender;
import de.atstck.kitly.cal.outbox.Wire;
import de.atstck.kitly.cal.support.Fixtures;
import de.atstck.kitly.cal.support.Harness;
import de.atstck.kitly.cal.support.Http;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole application over real HTTP in the mock profile: the wire contract the app in dsb-new
 * relies on (paths, credentials, JSON shapes, status codes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "kitly-cal.outbox.mode=inline",
        "kitly-cal.outbox.polling=false",
        "kitly-cal.auth.api-keys=" + Fixtures.LIVE_KEY,
        "kitly-cal.auth.sandbox-api-keys=" + Fixtures.SANDBOX_KEY,
        "kitly-cal.webhook.url=" + Fixtures.WEBHOOK_URL,
        "kitly-cal.webhook.secret=" + Fixtures.WEBHOOK_SECRET,
        "kitly-cal.mock.forward-webhooks=false",
})
@ActiveProfiles("mock")
class ApiTest {

    private static final String TUESDAY_RANGE = "start=2026-10-06T00:00:00.000Z&end=2026-10-06T23:59:59.999Z";
    private static final String TUESDAY_11 = "2026-10-06T09:00:00.000Z";

    @LocalServerPort
    int port;
    @Autowired
    MutableClock clock;
    @Autowired
    RecordingWebhookSender webhooks;
    @Autowired
    RecordingNotifier notifier;
    @Autowired
    RecordingCalendar calendar;

    private Http http;

    @BeforeEach
    void startOver() {
        http = new Http(port, Fixtures.LIVE_KEY);
        assertThat(http.send("POST", "/_mock/reset", null, (String) null).status()).isEqualTo(200);
        // Monday 5 October 2026, 09:00 Berlin.
        clock.setFixed(Instant.parse("2026-10-05T07:00:00Z"));
    }

    private String reserve(String start) {
        Http.Result created = http.post("/v2/bookings", Harness.bookingBody(start));
        assertThat(created.status()).isEqualTo(201);
        return (String) created.<Map<String, Object>>data().get("uid");
    }

    private List<String> slotStarts(String key) {
        Http.Result slots = http.get("/v2/slots?eventTypeId=1&" + TUESDAY_RANGE + "&timeZone=Europe/Berlin", key);
        assertThat(slots.status()).isEqualTo(200);
        return slots.<Map<String, List<Map<String, String>>>>data().values().stream()
                .flatMap(List::stream).map(slot -> slot.get("start")).toList();
    }

    @Test
    void rejectsRequestsWithoutOrWithAWrongKey() {
        Http.Result missing = http.get("/v2/bookings", null);
        assertThat(missing.status()).isEqualTo(401);
        // app/api/cal/slots/route.ts looks for exactly this text to show its own hint.
        assertThat(missing.errorMessage()).containsIgnoringCase("invalid api key");
        assertThat(missing.body()).containsEntry("status", "error").containsKey("timestamp").containsEntry("path", "/v2/bookings");
        assertThat(http.get("/v2/bookings", "kcal_live_wrong_0123456789abcdef").status()).isEqualTo(401);
        assertThat(http.post("/internal/outbox/run", null, null).status()).isEqualTo(401);
    }

    @Test
    void acceptsTheKeyTheThreeWaysTheAppSendsIt() {
        String path = "/v2/slots?eventTypeId=1&" + TUESDAY_RANGE;
        assertThat(http.get(path).status()).isEqualTo(200); // Authorization: Bearer <key>
        assertThat(http.send("GET", path, null, Map.of("Authorization", Fixtures.LIVE_KEY)).status()).isEqualTo(200);
        assertThat(http.send("GET", path, null, Map.of("x-cal-secret-key", Fixtures.LIVE_KEY)).status()).isEqualTo(200);
        // The slots route of the app sends both headers at once, plus the Cal version header.
        assertThat(http.send("GET", path, null, Map.of("Authorization", Fixtures.LIVE_KEY, "x-cal-secret-key", Fixtures.LIVE_KEY,
                "cal-api-version", "2024-09-04")).status()).isEqualTo(200);
    }

    @Test
    void rejectsCredentialsThatPointToDifferentWorlds() {
        Http.Result mixed = http.send("GET", "/v2/bookings", null,
                Map.of("Authorization", "Bearer " + Fixtures.LIVE_KEY, "x-cal-secret-key", Fixtures.SANDBOX_KEY));
        assertThat(mixed.status()).isEqualTo(401);
    }

    @Test
    void answersTheHealthCheckWithoutAKey() {
        Http.Result health = http.get("/health", null);
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body()).isEqualTo(Map.of("status", "ok"));
    }

    @Test
    void returnsSlotsGroupedByDateWithTheOffsetOfTheRequestedZone() {
        Http.Result slots = http.get("/v2/slots?eventTypeId=1&" + TUESDAY_RANGE + "&timeZone=Europe/Berlin");
        assertThat(slots.body()).isEqualTo(Map.of("status", "success", "data", Map.of("2026-10-06", List.of(
                Map.of("start", "2026-10-06T09:00:00.000+02:00"),
                Map.of("start", "2026-10-06T11:00:00.000+02:00"),
                Map.of("start", "2026-10-06T13:00:00.000+02:00"),
                Map.of("start", "2026-10-06T15:00:00.000+02:00")))));
    }

    @Test
    void addsTheEndWithFormatRangeAndDefaultsToUtc() {
        Http.Result slots = http.get("/v2/slots?eventTypeId=1&" + TUESDAY_RANGE + "&format=range");
        assertThat(slots.<Map<String, List<Map<String, String>>>>data().get("2026-10-06").get(0)).isEqualTo(
                Map.of("start", "2026-10-06T07:00:00.000+00:00", "end", "2026-10-06T09:00:00.000+00:00"));
    }

    @Test
    void answersAMonthQueryLikeTheAppSendsIt() {
        // app/api/cal/slots/route.ts asks for a whole month in UTC bounds.
        Http.Result month = http.get(
                "/v2/slots?eventTypeId=1&start=2026-10-01T00:00:00.000Z&end=2026-10-31T23:59:59.999Z&timeZone=Europe/Berlin");
        Map<String, List<Map<String, String>>> byDate = month.data();
        assertThat(byDate).doesNotContainKeys("2026-10-02", "2026-10-10", "2026-10-11"); // past Friday, weekend
        assertThat(byDate.get("2026-10-05")).hasSize(4);
        // After the clock change on 25 October the offset is +01:00 and 09:00 stays 09:00.
        assertThat(byDate.get("2026-10-26").get(0).get("start")).isEqualTo("2026-10-26T09:00:00.000+01:00");
    }

    @Test
    void reservesASlotAsPendingInTheShapeTheAppReads() {
        Http.Result created = http.post("/v2/bookings", Harness.bookingBody(Harness.TUESDAY_9));
        assertThat(created.status()).isEqualTo(201);
        Map<String, Object> booking = created.data();
        // lib/cal-booking.ts reads uid, status, start, end, createdAt and metadata.
        assertThat(booking)
                .containsEntry("uid", "mock-1")
                .containsEntry("status", "pending")
                .containsEntry("start", Harness.TUESDAY_9)
                .containsEntry("end", TUESDAY_11)
                .containsEntry("duration", 120)
                .containsEntry("eventTypeId", 1)
                .containsEntry("createdAt", "2026-10-05T07:00:00.000Z")
                .containsEntry("metadata", Map.of("source", "dsb-potenzialanalyse", "intakeHash", "hash_1", "tenantId", "tenant_1"));
        assertThat(booking.get("attendees")).isEqualTo(List.of(Map.of(
                "name", "Erika Beispiel", "email", "erika@example.com", "timeZone", "Europe/Berlin", "language", "de", "absent", false)));
        assertThat(booking).doesNotContainKey("sandbox");
        assertThat(webhooks.attempts()).isEmpty();

        assertThat(slotStarts(Fixtures.LIVE_KEY)).doesNotContain("2026-10-06T09:00:00.000+02:00").hasSize(3);
        Http.Result second = http.post("/v2/bookings", Harness.bookingBody(Harness.TUESDAY_9));
        assertThat(second.status()).isEqualTo(400);
        assertThat(second.errorMessage()).contains("not available");
    }

    @Test
    void confirmsAndOnlyThenTriggersWebhookMailAndCalendarEntry() {
        String uid = reserve(Harness.TUESDAY_9);
        clock.advance(Duration.ofMinutes(20));
        Http.Result confirmed = http.post("/v2/bookings/" + uid + "/confirm", Map.of());
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.<Map<String, Object>>data()).containsEntry("uid", uid).containsEntry("status", "accepted");

        assertThat(webhooks.sent()).hasSize(1);
        assertThat(webhooks.sent().get(0).url()).isEqualTo(Fixtures.WEBHOOK_URL);
        assertThat(webhooks.sent().get(0).headers().get("x-cal-signature-256"))
                .isEqualTo(Wire.sign(webhooks.sent().get(0).body(), Fixtures.WEBHOOK_SECRET));
        assertThat(webhooks.sent().get(0).body()).contains("\"triggerEvent\":\"BOOKING_CREATED\"", "\"uid\":\"" + uid + "\"");
        assertThat(notifier.confirmations()).hasSize(1);
        assertThat(calendar.events()).hasSize(1);

        // Reloading /zahlung/success confirms again: no error, no second webhook.
        assertThat(http.post("/v2/bookings/" + uid + "/confirm", Map.of()).status()).isEqualTo(200);
        assertThat(webhooks.sent()).hasSize(1);
        // The app sends an empty JSON object; no body at all works as well.
        assertThat(http.post("/v2/bookings/" + uid + "/confirm", null).status()).isEqualTo(200);
    }

    @Test
    void declinesOnlyPendingBookingsWithTheStatusCodesTheAppTreatsAsNotReleased() {
        String paid = reserve(Harness.TUESDAY_9);
        http.post("/v2/bookings/" + paid + "/confirm", Map.of());
        // lib/cal-booking.ts: 400, 404 and 409 mean "no longer pending", anything else is an error.
        assertThat(http.post("/v2/bookings/" + paid + "/decline", Map.of("reason", "expired")).status()).isEqualTo(400);
        assertThat(http.post("/v2/bookings/unknown/decline", Map.of("reason", "x")).status()).isEqualTo(404);
        assertThat(http.get("/v2/bookings/" + paid).<Map<String, Object>>data()).containsEntry("status", "accepted");

        String unpaid = reserve(TUESDAY_11);
        Http.Result declined = http.post("/v2/bookings/" + unpaid + "/decline", Map.of("reason", "Zahlung nicht abgeschlossen"));
        assertThat(declined.status()).isEqualTo(200);
        assertThat(declined.<Map<String, Object>>data())
                .containsEntry("status", "rejected").containsEntry("rejectionReason", "Zahlung nicht abgeschlossen");
        assertThat(slotStarts(Fixtures.LIVE_KEY)).contains("2026-10-06T11:00:00.000+02:00");
        Http.Result lateConfirm = http.post("/v2/bookings/" + unpaid + "/confirm", Map.of());
        assertThat(lateConfirm.status()).isEqualTo(400);
        assertThat(http.post("/v2/bookings/" + reserve(TUESDAY_11) + "/decline", null).status()).isEqualTo(200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsUnconfirmedBookingsForTheHousekeepingJob() {
        String first = reserve(Harness.TUESDAY_9);
        clock.advance(Duration.ofMinutes(1));
        String second = reserve(TUESDAY_11);
        http.post("/v2/bookings/" + second + "/confirm", Map.of());

        Http.Result pending = http.get("/v2/bookings?status=unconfirmed&take=100&skip=0");
        assertThat(pending.status()).isEqualTo(200);
        List<Map<String, Object>> rows = pending.data();
        assertThat(rows).hasSize(1);
        // The job needs uid, status, createdAt and metadata.source.
        assertThat(rows.get(0)).containsEntry("uid", first).containsEntry("status", "pending")
                .containsEntry("createdAt", "2026-10-05T07:00:00.000Z");
        assertThat((Map<String, Object>) rows.get(0).get("metadata")).containsEntry("source", "dsb-potenzialanalyse");
        assertThat(http.get("/v2/bookings?status=unconfirmed&take=100&skip=1").<List<Object>>data()).isEmpty();
        assertThat(http.get("/v2/bookings?status=everything").status()).isEqualTo(400);
    }

    @Test
    void keepsSandboxKeysInTheirOwnWorld() {
        Http.Result test = http.post("/v2/bookings", Harness.bookingBody(Harness.TUESDAY_9), Fixtures.SANDBOX_KEY);
        assertThat(test.status()).isEqualTo(201);
        String uid = (String) test.<Map<String, Object>>data().get("uid");
        assertThat(test.<Map<String, Object>>data()).containsEntry("sandbox", true);
        assertThat(http.get("/internal/whoami", Fixtures.SANDBOX_KEY).<Map<String, Object>>data()).containsEntry("sandbox", true);
        assertThat(http.get("/internal/whoami").<Map<String, Object>>data()).containsEntry("sandbox", false);

        assertThat(slotStarts(Fixtures.LIVE_KEY)).contains("2026-10-06T09:00:00.000+02:00");
        assertThat(slotStarts(Fixtures.SANDBOX_KEY)).doesNotContain("2026-10-06T09:00:00.000+02:00");
        assertThat(http.get("/v2/bookings/" + uid).status()).isEqualTo(404);
        assertThat(http.post("/v2/bookings/" + uid + "/confirm", Map.of()).status()).isEqualTo(404);

        assertThat(http.post("/v2/bookings/" + uid + "/confirm", Map.of(), Fixtures.SANDBOX_KEY).status()).isEqualTo(200);
        assertThat(webhooks.attempts()).isEmpty();
        assertThat(notifier.confirmations()).isEmpty();
        assertThat(calendar.events()).isEmpty();
    }

    @Test
    void answersBrokenRequestsInTheErrorShapeTheAppReads() {
        Http.Result notJson = http.send("POST", "/v2/bookings", "{not json", Fixtures.LIVE_KEY);
        assertThat(notJson.status()).isEqualTo(400);
        assertThat(notJson.body()).containsEntry("status", "error");
        assertThat(notJson.errorMessage()).isNotBlank();

        assertThat(http.send("POST", "/v2/bookings", "[1,2]", Fixtures.LIVE_KEY).status()).isEqualTo(400);
        assertThat(http.post("/v2/bookings", null).status()).isEqualTo(400);

        Http.Result unknown = http.post("/v2/bookings/mock-1/cancel", Map.of());
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.errorMessage()).isEqualTo("Cannot POST /v2/bookings/mock-1/cancel");
        assertThat(http.post("/v2/slots", Map.of()).status()).isEqualTo(404);

        Http.Result tooLarge = http.post("/v2/bookings", Map.of("padding", "x".repeat(40_000)));
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(http.get("/v2/slots?eventTypeId=1&start=tomorrow&end=2026-10-06").status()).isEqualTo(400);
    }

    @Test
    void exposesDeliveryStateForOperationsWithoutCustomerData() {
        webhooks.failWith(new IllegalStateException("Receiver answered 500"));
        String uid = reserve(Harness.TUESDAY_9);
        assertThat(http.post("/v2/bookings/" + uid + "/confirm", Map.of()).status()).isEqualTo(200);

        Http.Result overview = http.get("/internal/outbox?status=pending");
        assertThat(overview.status()).isEqualTo(200);
        List<Map<String, Object>> events = overview.data();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("kind", "webhook").containsEntry("attempts", 1)
                .containsEntry("lastError", "Receiver answered 500").doesNotContainKey("payload");
        assertThat(overview.raw()).doesNotContain("erika@example.com");
        assertThat(http.get("/internal/outbox?status=nonsense").status()).isEqualTo(400);
        assertThat(http.get("/internal/outbox", Fixtures.SANDBOX_KEY).<List<Object>>data()).isEmpty();

        webhooks.failWith(null);
        clock.advance(Duration.ofMinutes(1));
        assertThat(http.post("/internal/outbox/run", null).<Map<String, Object>>data())
                .isEqualTo(Map.of("delivered", 1, "retrying", 0, "dead", 0));
        assertThat(webhooks.sent()).hasSize(1);
    }

    @Test
    void sendsASignedPingOnRequest() {
        assertThat(http.post("/internal/webhook/ping", null).status()).isEqualTo(200);
        assertThat(webhooks.sent()).hasSize(1);
        assertThat(webhooks.sent().get(0).body()).contains("\"triggerEvent\":\"PING\"");
        assertThat(webhooks.sent().get(0).headers().get("x-cal-signature-256"))
                .isEqualTo(Wire.sign(webhooks.sent().get(0).body(), Fixtures.WEBHOOK_SECRET));

        webhooks.failWith(new IllegalStateException("Receiver answered 401: Invalid webhook signature."));
        Http.Result failed = http.post("/internal/webhook/ping", null);
        assertThat(failed.status()).isEqualTo(502);
        assertThat(failed.errorMessage()).contains("401");
        // No sandbox URL is configured, so there is nothing to ping for a sandbox key.
        assertThat(http.post("/internal/webhook/ping", null, Fixtures.SANDBOX_KEY).status()).isEqualTo(400);
    }

    @Test
    @SuppressWarnings("unchecked")
    void offersTestControlsInTheMockProfile() {
        Http.Result busy = http.send("POST", "/_mock/busy", "{\"start\":\"2026-10-06T08:00:00Z\",\"end\":\"2026-10-06T08:30:00Z\"}", Map.of());
        assertThat(busy.status()).isEqualTo(200);
        assertThat(slotStarts(Fixtures.LIVE_KEY)).doesNotContain("2026-10-06T09:00:00.000+02:00").hasSize(3);

        String uid = reserve(TUESDAY_11);
        http.post("/v2/bookings/" + uid + "/confirm", Map.of());
        Http.Result advanced = http.send("POST", "/_mock/clock", "{\"advanceMinutes\":200}", Map.of());
        assertThat(advanced.body()).containsEntry("now", "2026-10-05T10:20:00.000Z");

        Map<String, Object> state = http.get("/_mock/state", null).body();
        assertThat((List<Object>) state.get("bookings")).hasSize(1);
        assertThat((List<Object>) state.get("webhooks")).hasSize(1);
        assertThat((List<Map<String, Object>>) state.get("confirmationMails"))
                .containsExactly(Map.of("to", "erika@example.com", "uid", uid));
        assertThat((List<Object>) state.get("calendarEntries")).hasSize(1);

        assertThat(http.send("POST", "/_mock/reset", null, Map.of()).status()).isEqualTo(200);
        Map<String, Object> empty = http.get("/_mock/state", null).body();
        assertThat((List<Object>) empty.get("bookings")).isEmpty();
        assertThat((List<Object>) empty.get("webhooks")).isEmpty();
        assertThat(http.send("POST", "/_mock/clock", "{\"now\":\"2026-10-05T07:00:00Z\",\"freeze\":true}", Map.of()).body())
                .containsEntry("now", "2026-10-05T07:00:00.000Z");
        assertThat(slotStarts(Fixtures.LIVE_KEY)).hasSize(4);
    }
}
