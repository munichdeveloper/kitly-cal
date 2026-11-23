package de.atstck.kitly.cal.domain;

/** An error with an HTTP status, a stable code and a message that is safe to show to the caller. */
public class ServiceException extends RuntimeException {

    private final int status;
    private final String code;

    public ServiceException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ServiceException badRequest(String message) {
        return new ServiceException(400, "BAD_REQUEST", message);
    }

    public static ServiceException notFound(String message) {
        return new ServiceException(404, "NOT_FOUND", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
