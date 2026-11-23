package de.atstck.kitly.cal.service;

/** Which world a request acts in. Decided by the API key, never by the request body. */
public record RequestContext(boolean sandbox) {
}
