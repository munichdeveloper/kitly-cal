package de.atstck.kitly.cal.config;

/** Source of booking uids. The default is random; the mock profile counts up for readable test output. */
@FunctionalInterface
public interface UidGenerator {

    String next();
}
