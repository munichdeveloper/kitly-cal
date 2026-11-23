package de.atstck.kitly.cal.web;

import de.atstck.kitly.cal.domain.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure into the JSON error shape the app reads. Never leaks internals. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ServiceException.class)
    ResponseEntity<Map<String, Object>> service(ServiceException error, HttpServletRequest request) {
        if (error.status() >= 500) {
            log.error("request failed path={} status={} code={}", request.getRequestURI(), error.status(), error.code());
        }
        return respond(error.status(), error.code(), error.getMessage(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<Map<String, Object>> unreadable(Exception error, HttpServletRequest request) {
        return respond(400, "BAD_REQUEST", "Request body must be a valid JSON object.", request);
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<Map<String, Object>> notFound(Exception error, HttpServletRequest request) {
        return respond(404, "NOT_FOUND", "Cannot " + request.getMethod() + " " + request.getRequestURI(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception error, HttpServletRequest request) {
        log.error("unexpected error path={}", request.getRequestURI(), error);
        return respond(500, "INTERNAL", "Internal server error.", request);
    }

    private ResponseEntity<Map<String, Object>> respond(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(ApiError.body(code, message, request.getRequestURI(), clock.instant()));
    }
}
