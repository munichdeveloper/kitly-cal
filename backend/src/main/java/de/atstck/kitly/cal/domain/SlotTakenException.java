package de.atstck.kitly.cal.domain;

/** Thrown by a store when the block range collides with another blocking booking. */
public class SlotTakenException extends RuntimeException {

    public SlotTakenException() {
        super("Slot is already taken.");
    }
}
