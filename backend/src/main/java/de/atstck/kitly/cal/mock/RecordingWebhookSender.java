package de.atstck.kitly.cal.mock;

import de.atstck.kitly.cal.outbox.WebhookSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records webhooks instead of sending them, or records and forwards them when a delegate is given.
 * {@code failWith} simulates a broken receiver.
 */
public class RecordingWebhookSender implements WebhookSender {

    private final WebhookSender delegate;
    private final List<WebhookRequest> attempts = new CopyOnWriteArrayList<>();
    private final List<WebhookRequest> sent = new CopyOnWriteArrayList<>();
    private volatile Exception failWith;

    public RecordingWebhookSender() {
        this(null);
    }

    public RecordingWebhookSender(WebhookSender delegate) {
        this.delegate = delegate;
    }

    @Override
    public void send(WebhookRequest request) throws Exception {
        attempts.add(request);
        if (failWith != null) {
            throw failWith;
        }
        if (delegate != null) {
            delegate.send(request);
        }
        sent.add(request);
    }

    /** Every attempt, including failed ones. */
    public List<WebhookRequest> attempts() {
        return attempts;
    }

    /** Everything that was delivered. */
    public List<WebhookRequest> sent() {
        return sent;
    }

    public void failWith(Exception error) {
        failWith = error;
    }

    public void reset() {
        attempts.clear();
        sent.clear();
        failWith = null;
    }
}
