package de.atstck.kitly.cal.mock;

import de.atstck.kitly.cal.outbox.CalendarWriter;
import de.atstck.kitly.cal.outbox.ConfirmationContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records calendar entries instead of creating them. */
public class RecordingCalendar implements CalendarWriter {

    private final List<ConfirmationContext> events = new CopyOnWriteArrayList<>();
    private volatile Exception failWith;

    @Override
    public void createEvent(ConfirmationContext context) throws Exception {
        if (failWith != null) {
            throw failWith;
        }
        events.add(context);
    }

    public List<ConfirmationContext> events() {
        return events;
    }

    public void failWith(Exception error) {
        failWith = error;
    }

    public void reset() {
        events.clear();
        failWith = null;
    }
}
