package de.atstck.kitly.cal.outbox;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.domain.Booking;

public record ConfirmationContext(Booking booking, CalConfig.EventType eventType, CalConfig.Organizer organizer) {
}
