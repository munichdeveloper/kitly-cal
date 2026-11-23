package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.atstck.kitly.cal.outbox.HttpWebhookSender;
import de.atstck.kitly.cal.outbox.WebhookSender.WebhookRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real sender against a real local HTTP server. */
class HttpWebhookSenderTest {

    private HttpServer server;
    private String base;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("x-test") + " " + body);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/moved", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", base + "/ok");
            exchange.sendResponseHeaders(308, -1);
            exchange.close();
        });
        server.createContext("/denied", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] answer = "{\"error\":\"Invalid webhook signature.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private WebhookRequest request(String path) {
        return new WebhookRequest(base + path, "{\"a\":\"ü\"}", Map.of("x-test", "1", "Content-Type", "application/json"));
    }

    @Test
    void postsTheBodyUnchangedAndAcceptsAny2xx() throws Exception {
        new HttpWebhookSender(Duration.ofSeconds(2)).send(request("/ok"));
        assertThat(received).containsExactly("POST 1 {\"a\":\"ü\"}");
    }

    @Test
    void treatsARedirectAsFailureAndNamesTheTargetInsteadOfFollowingIt() {
        assertThatThrownBy(() -> new HttpWebhookSender(Duration.ofSeconds(2)).send(request("/moved")))
                .hasMessageContaining("308").hasMessageContaining(base + "/ok");
        assertThat(received).isEmpty();
    }

    @Test
    void failsOnErrorAnswersAndIncludesTheReceiversMessage() {
        assertThatThrownBy(() -> new HttpWebhookSender(Duration.ofSeconds(2)).send(request("/denied")))
                .hasMessageContaining("401").hasMessageContaining("Invalid webhook signature");
    }

    @Test
    void givesUpAfterTheTimeout() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> new HttpWebhookSender(Duration.ofMillis(300)).send(request("/slow")))
                .isInstanceOf(java.net.http.HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
    }
}
