package de.atstck.kitly.cal.outbox;

/**
 * Tells the customer and the host about a confirmed booking (cal.eu did this by e-mail).
 * No implementation exists yet: declare a Spring bean of this type to switch the effect on.
 */
public interface Notifier {

    /** @param ics calendar invitation to attach */
    void bookingConfirmed(ConfirmationContext context, String ics) throws Exception;
}
