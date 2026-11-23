package de.atstck.kitly.cal.outbox;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends webhooks over HTTP. Redirects are not followed: a redirected POST often arrives as a GET or
 * without its body, so a 3xx answer counts as a failure and names the target to configure instead.
 */
public class HttpWebhookSender implements WebhookSender {

    private final HttpClient client;
    private final Duration timeout;

    public HttpWebhookSender(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void send(WebhookRequest request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(request.body()));
        request.headers().forEach(builder::header);

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status >= 300 && status < 400) {
            throw new IllegalStateException("Receiver answered " + status + " with a redirect to "
                    + response.headers().firstValue("location").orElse("?") + ". Configure the final URL.");
        }
        String body = response.body() == null ? "" : response.body();
        throw new IllegalStateException("Receiver answered " + status + ": " + body.substring(0, Math.min(200, body.length())));
    }
}
