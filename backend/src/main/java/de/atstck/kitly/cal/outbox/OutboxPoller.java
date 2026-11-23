package de.atstck.kitly.cal.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Picks up retries and anything a crashed run left behind. Switch off with kitly-cal.outbox.polling=false. */
@Component
@ConditionalOnProperty(name = "kitly-cal.outbox.polling", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private final OutboxDispatcher dispatcher;

    public OutboxPoller(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${kitly-cal.outbox.poll-interval-ms:15000}", initialDelayString = "5000")
    public void poll() {
        dispatcher.runQuietly();
    }
}
