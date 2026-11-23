package de.atstck.kitly.cal.config;

/** Serialises an object tree to JSON. */
@FunctionalInterface
public interface JsonWriter {

    String write(Object value);
}
