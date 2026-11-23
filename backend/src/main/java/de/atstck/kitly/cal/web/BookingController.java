package de.atstck.kitly.cal.web;

import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.Interval;
import de.atstck.kitly.cal.domain.Iso;
import de.atstck.kitly.cal.outbox.Wire;
import de.atstck.kitly.cal.service.BookingService;
import de.atstck.kitly.cal.service.RequestContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The part of the Cal.com API v2 that dsb-new calls. Paths, parameters and response shapes follow
 * Cal, so the app only needs a different CALCOM_API_BASE_URL.
 */
@RestController
public class BookingController {

    private final BookingService service;
    private final CalConfig config;

    public BookingController(BookingService service, CalConfig config) {
        this.service = service;
        this.config = config;
    }

    @GetMapping("/v2/slots")
    ResponseEntity<Map<String, Object>> slots(
            @RequestParam Map<String, String> query,
            @RequestAttribute(ApiKeyFilter.CONTEXT_ATTRIBUTE) RequestContext context) {
        BookingService.Slots result = service.getSlots(query, context);
        boolean ranged = "range".equals(query.get("format"));
        Map<String, List<Map<String, String>>> byDate = new LinkedHashMap<>();
        for (Interval slot : result.slots()) {
            String date = slot.start().atZone(result.timeZone()).toLocalDate().toString();
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("start", Iso.withOffset(slot.start(), result.timeZone()));
            if (ranged) {
                entry.put("end", Iso.withOffset(slot.end(), result.timeZone()));
            }
            byDate.computeIfAbsent(date, key -> new ArrayList<>()).add(entry);
        }
        return Responses.success(byDate, 200);
    }

    @PostMapping("/v2/bookings")
    ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(ApiKeyFilter.CONTEXT_ATTRIBUTE) RequestContext context) {
        return Responses.success(wire(service.createBooking(body, context)), 201);
    }

    @GetMapping("/v2/bookings")
    ResponseEntity<Map<String, Object>> list(
            @RequestParam Map<String, String> query,
            @RequestAttribute(ApiKeyFilter.CONTEXT_ATTRIBUTE) RequestContext context) {
        List<Map<String, Object>> bookings = service.listBookings(query, context).stream().map(this::wire).toList();
        Map<String, Object> body = Responses.envelope(bookings);
        body.put("pagination", Map.of("returnedItems", bookings.size()));
        return Responses.send(body, 200);
    }

    @GetMapping("/v2/bookings/{uid}")
    ResponseEntity<Map<String, Object>> get(
            @PathVariable String uid,
            @RequestAttribute(ApiKeyFilter.CONTEXT_ATTRIBUTE) RequestContext context) {
        return Responses.success(wire(service.getBooking(uid, context)), 200);
    }

    @PostMapping("/v2/bookings/{uid}/confirm")
    ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String uid,
            @RequestAttribute(ApiKeyFilter.CONTEXT_ATTRIBUTE) RequestContext context) {
        return Responses.success(wire(service.confirmBooking(uid, context)), 200);
    }

    @PostMapping("/v2/bookings/{uid}/decline")
    ResponseEntity<Map<String, Object>> decline(
            @PathVariable String uid,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(ApiKeyFilter.CONTEXT_ATTRIBUTE) RequestContext context) {
        return Responses.success(wire(service.declineBooking(uid, body, context)), 200);
    }

    private Map<String, Object> wire(Booking booking) {
        return Wire.booking(booking, config);
    }
}
