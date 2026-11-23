package de.atstck.kitly.cal.store;

import de.atstck.kitly.cal.domain.Attendee;
import de.atstck.kitly.cal.domain.Booking;
import de.atstck.kitly.cal.domain.BookingStatus;
import de.atstck.kitly.cal.domain.Interval;
import de.atstck.kitly.cal.domain.NewBooking;
import de.atstck.kitly.cal.domain.NewOutboxEvent;
import de.atstck.kitly.cal.domain.OutboxEvent;
import de.atstck.kitly.cal.domain.OutboxKind;
import de.atstck.kitly.cal.domain.OutboxStatus;
import de.atstck.kitly.cal.domain.SlotTakenException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL store. The schema lives in {@code db/migration}; its exclusion constraints are the real
 * guard against double bookings: even when two requests race past the availability check, the
 * database accepts only one of them.
 */
public class JdbcBookingStore implements BookingStore {

    /** SQLSTATE of a violated exclusion constraint. */
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final TypeReference<LinkedHashMap<String, String>> METADATA_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    private final RowMapper<Booking> bookingMapper = this::mapBooking;
    private final RowMapper<OutboxEvent> outboxMapper = JdbcBookingStore::mapOutbox;

    public JdbcBookingStore(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public Booking createBooking(NewBooking input) {
        try {
            return jdbc.sql("""
                    insert into bookings (
                      uid, sandbox, event_type_id, status, starts_at, ends_at, block_start, block_end,
                      attendee_name, attendee_email, attendee_time_zone, attendee_language,
                      metadata, created_at, updated_at
                    ) values (
                      :uid, :sandbox, :eventTypeId, 'pending', :start, :end, :blockStart, :blockEnd,
                      :name, :email, :timeZone, :language,
                      cast(:metadata as jsonb), :createdAt, :createdAt
                    )
                    returning *
                    """)
                    .param("uid", input.uid())
                    .param("sandbox", input.sandbox())
                    .param("eventTypeId", input.eventTypeId())
                    .param("start", utc(input.start()))
                    .param("end", utc(input.end()))
                    .param("blockStart", utc(input.blockStart()))
                    .param("blockEnd", utc(input.blockEnd()))
                    .param("name", input.attendee().name())
                    .param("email", input.attendee().email())
                    .param("timeZone", input.attendee().timeZone())
                    .param("language", input.attendee().language())
                    .param("metadata", json.writeValueAsString(input.metadata()))
                    .param("createdAt", utc(input.createdAt()))
                    .query(bookingMapper)
                    .single();
        } catch (DataAccessException e) {
            if (isExclusionViolation(e)) {
                throw new SlotTakenException();
            }
            throw e;
        }
    }

    @Override
    public Optional<Booking> getBooking(String uid, boolean sandbox) {
        return jdbc.sql("select * from bookings where uid = :uid and sandbox = :sandbox")
                .param("uid", uid)
                .param("sandbox", sandbox)
                .query(bookingMapper)
                .optional();
    }

    @Override
    public Optional<TransitionResult> transition(Transition transition) {
        return transactions.execute(status -> {
            Optional<Booking> current = jdbc
                    .sql("select * from bookings where uid = :uid and sandbox = :sandbox for update")
                    .param("uid", transition.uid())
                    .param("sandbox", transition.sandbox())
                    .query(bookingMapper)
                    .optional();
            if (current.isEmpty()) {
                return Optional.<TransitionResult>empty();
            }
            if (current.get().status() != transition.from()) {
                return Optional.of(new TransitionResult(current.get(), false));
            }

            Booking next = current.get().withTransition(transition.to(), transition.at(), transition.reason());
            Booking updated = jdbc.sql("""
                    update bookings set
                      status = :status,
                      updated_at = :updatedAt,
                      confirmed_at = cast(:confirmedAt as timestamptz),
                      rejected_at = cast(:rejectedAt as timestamptz),
                      rejection_reason = cast(:reason as text)
                    where uid = :uid
                    returning *
                    """)
                    .param("status", next.status().wire())
                    .param("updatedAt", utc(next.updatedAt()))
                    .param("confirmedAt", utc(next.confirmedAt()))
                    .param("rejectedAt", utc(next.rejectedAt()))
                    .param("reason", next.rejectionReason())
                    .param("uid", next.uid())
                    .query(bookingMapper)
                    .single();

            for (NewOutboxEvent event : transition.outbox()) {
                jdbc.sql("""
                        insert into outbox (booking_uid, sandbox, kind, payload, next_attempt_at, created_at)
                        values (:uid, :sandbox, :kind, :payload, :at, :at)
                        """)
                        .param("uid", updated.uid())
                        .param("sandbox", updated.sandbox())
                        .param("kind", event.kind().wire())
                        .param("payload", event.payload())
                        .param("at", utc(transition.at()))
                        .update();
            }
            return Optional.of(new TransitionResult(updated, true));
        });
    }

    @Override
    public List<Booking> listBookings(BookingFilter filter) {
        List<String> statuses = filter.statuses() == null
                ? List.of("pending", "accepted", "rejected")
                : filter.statuses().stream().map(BookingStatus::wire).toList();
        return jdbc.sql("""
                select * from bookings
                where sandbox = :sandbox
                  and status in (:statuses)
                  and (cast(:endsAfter as timestamptz) is null or ends_at > cast(:endsAfter as timestamptz))
                  and (cast(:endsBefore as timestamptz) is null or ends_at <= cast(:endsBefore as timestamptz))
                order by created_at, id
                limit :take offset :skip
                """)
                .param("sandbox", filter.sandbox())
                .param("statuses", statuses)
                .param("endsAfter", utc(filter.endsAfter()))
                .param("endsBefore", utc(filter.endsBefore()))
                .param("take", filter.take())
                .param("skip", filter.skip())
                .query(bookingMapper)
                .list();
    }

    @Override
    public List<Interval> listBlockingIntervals(Interval range, boolean sandbox) {
        return jdbc.sql("""
                select block_start, block_end from bookings
                where sandbox = :sandbox
                  and status in ('pending', 'accepted')
                  and block_start < :rangeEnd and block_end > :rangeStart
                """)
                .param("sandbox", sandbox)
                .param("rangeStart", utc(range.start()))
                .param("rangeEnd", utc(range.end()))
                .query((rs, row) -> new Interval(instant(rs, "block_start"), instant(rs, "block_end")))
                .list();
    }

    @Override
    public List<OutboxEvent> claimOutbox(Instant now, int limit, Duration lease) {
        return jdbc.sql("""
                with claimed as (
                  update outbox set locked_until = :lockedUntil
                  where id in (
                    select id from outbox
                    where status = 'pending'
                      and next_attempt_at <= :now
                      and (locked_until is null or locked_until < :now)
                    order by id
                    limit :limit
                    for update skip locked
                  )
                  returning *
                )
                select * from claimed order by id
                """)
                .param("lockedUntil", utc(now.plus(lease)))
                .param("now", utc(now))
                .param("limit", limit)
                .query(outboxMapper)
                .list();
    }

    @Override
    public void recordOutboxSuccess(long id, Instant at) {
        jdbc.sql("""
                update outbox set status = 'delivered', attempts = attempts + 1, locked_until = null,
                  last_error = null, delivered_at = :at
                where id = :id
                """)
                .param("at", utc(at))
                .param("id", id)
                .update();
    }

    @Override
    public void recordOutboxFailure(long id, Instant at, String error, Instant retryAt) {
        jdbc.sql("""
                update outbox set
                  status = :status,
                  attempts = attempts + 1,
                  locked_until = null,
                  last_error = :error,
                  next_attempt_at = coalesce(cast(:retryAt as timestamptz), next_attempt_at)
                where id = :id
                """)
                .param("status", retryAt == null ? "dead" : "pending")
                .param("error", error)
                .param("retryAt", utc(retryAt))
                .param("id", id)
                .update();
    }

    @Override
    public List<OutboxEvent> listOutbox(OutboxStatus status, int take) {
        return jdbc.sql("""
                select * from (
                  select * from outbox
                  where cast(:status as text) is null or status = cast(:status as text)
                  order by id desc
                  limit :take
                ) newest order by id
                """)
                .param("status", status == null ? null : status.wire())
                .param("take", take)
                .query(outboxMapper)
                .list();
    }

    @Override
    public void ping() {
        jdbc.sql("select 1").query(Integer.class).single();
    }

    private Booking mapBooking(ResultSet rs, int row) throws SQLException {
        String metadata = rs.getString("metadata");
        return new Booking(
                rs.getLong("id"),
                rs.getString("uid"),
                rs.getBoolean("sandbox"),
                rs.getInt("event_type_id"),
                BookingStatus.fromWire(rs.getString("status")),
                instant(rs, "starts_at"),
                instant(rs, "ends_at"),
                instant(rs, "block_start"),
                instant(rs, "block_end"),
                new Attendee(rs.getString("attendee_name"), rs.getString("attendee_email"),
                        rs.getString("attendee_time_zone"), rs.getString("attendee_language")),
                metadata == null ? Map.of() : json.readValue(metadata, METADATA_TYPE),
                rs.getString("rejection_reason"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "confirmed_at"),
                instant(rs, "rejected_at"));
    }

    private static OutboxEvent mapOutbox(ResultSet rs, int row) throws SQLException {
        return new OutboxEvent(
                rs.getLong("id"),
                rs.getString("booking_uid"),
                rs.getBoolean("sandbox"),
                OutboxKind.fromWire(rs.getString("kind")),
                rs.getString("payload"),
                OutboxStatus.fromWire(rs.getString("status")),
                rs.getInt("attempts"),
                instant(rs, "next_attempt_at"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instant(rs, "delivered_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static boolean isExclusionViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
