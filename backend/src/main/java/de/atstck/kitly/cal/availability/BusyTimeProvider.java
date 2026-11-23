package de.atstck.kitly.cal.availability;

import de.atstck.kitly.cal.domain.Interval;
import java.util.List;

/**
 * Source of appointments that are not bookings of this service, e.g. the host's own calendar.
 * No implementation exists yet: without a bean of this type only bookings of this service block a slot.
 */
public interface BusyTimeProvider {

    List<Interval> getBusy(Interval range) throws Exception;
}
