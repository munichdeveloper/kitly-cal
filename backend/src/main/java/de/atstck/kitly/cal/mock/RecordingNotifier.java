package de.atstck.kitly.cal.mock;

import de.atstck.kitly.cal.outbox.ConfirmationContext;
import de.atstck.kitly.cal.outbox.Notifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records confirmation mails instead of sending them. */
public class RecordingNotifier implements Notifier {

    public record Confirmation(ConfirmationContext context, String ics) {
    }

    private final List<Confirmation> confirmations = new CopyOnWriteArrayList<>();
    private volatile Exception failWith;

    @Override
    public void bookingConfirmed(ConfirmationContext context, String ics) throws Exception {
        if (failWith != null) {
            throw failWith;
        }
        confirmations.add(new Confirmation(context, ics));
    }

    public List<Confirmation> confirmations() {
        return confirmations;
    }

    public void failWith(Exception error) {
        failWith = error;
    }

    public void reset() {
        confirmations.clear();
        failWith = null;
    }
}
