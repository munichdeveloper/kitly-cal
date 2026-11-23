package de.atstck.kitly.cal;

import de.atstck.kitly.cal.store.BookingStore;
import de.atstck.kitly.cal.store.InMemoryBookingStore;

class InMemoryBookingStoreTest extends StoreContract {

    private final InMemoryBookingStore store = new InMemoryBookingStore();

    @Override
    BookingStore store() {
        return store;
    }
}
