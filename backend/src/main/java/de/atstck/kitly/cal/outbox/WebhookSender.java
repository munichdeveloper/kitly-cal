package de.atstck.kitly.cal.outbox;

import java.util.Map;

public interface WebhookSender {

    /** Returns normally on a 2xx answer and throws on everything else. */
    void send(WebhookRequest request) throws Exception;

    record WebhookRequest(String url, String body, Map<String, String> headers) {
    }
}
