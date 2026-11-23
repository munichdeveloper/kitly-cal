package de.atstck.kitly.cal;

import static org.assertj.core.api.Assertions.assertThat;

import de.atstck.kitly.cal.availability.SlotCalculator;
import de.atstck.kitly.cal.config.CalConfig;
import de.atstck.kitly.cal.config.CalProperties;
import de.atstck.kitly.cal.domain.Interval;
import de.atstck.kitly.cal.support.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlotCalculatorTest {

    /** Monday 5 October 2026, 08:00 Berlin (summer time, UTC+2). */
    private static final Instant NOW = Instant.parse("2026-10-05T06:00:00Z");

    private static List<String> slots(CalConfig config, String from, String to, Instant now, List<Interval> busy) {
        return SlotCalculator.compute(new SlotCalculator.Query(
                        config.eventTypes().get(0), config, Instant.parse(from), Instant.parse(to), now, busy))
                .stream().map(slot -> slot.start().toString()).toList();
    }

    private static List<String> monday(CalConfig config, Instant now, List<Interval> busy) {
        return slots(config, "2026-10-05T00:00:00Z", "2026-10-05T23:59:59Z", now, busy);
    }

    private static Interval busy(String start, String end) {
        return new Interval(Instant.parse(start), Instant.parse(end));
    }

    @Test
    void offersTheSlotsOfAWorkingDayInTheScheduleTimeZone() {
        CalConfig config = Fixtures.config();
        assertThat(monday(config, NOW, List.of())).containsExactly(
                "2026-10-05T07:00:00Z", "2026-10-05T09:00:00Z", "2026-10-05T11:00:00Z", "2026-10-05T13:00:00Z");
        Interval first = SlotCalculator.compute(new SlotCalculator.Query(config.eventTypes().get(0), config,
                Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-05T23:59:59Z"), NOW, List.of())).get(0);
        assertThat(Duration.between(first.start(), first.end())).isEqualTo(Duration.ofMinutes(120));
    }

    @Test
    void offersNothingOnDaysWithoutAWindow() {
        assertThat(slots(Fixtures.config(), "2026-10-10T00:00:00Z", "2026-10-11T23:59:59Z", NOW, List.of())).isEmpty();
    }

    @Test
    void keepsTheLocalStartTimeWhenTheClocksChange() {
        CalConfig config = Fixtures.config();
        // 2026: summer time ends on 25 October and starts on 29 March.
        assertThat(slots(config, "2026-10-23T00:00:00Z", "2026-10-23T23:59:59Z", NOW, List.of()).get(0))
                .isEqualTo("2026-10-23T07:00:00Z"); // 09:00 CEST
        assertThat(slots(config, "2026-10-26T00:00:00Z", "2026-10-26T23:59:59Z", NOW, List.of()).get(0))
                .isEqualTo("2026-10-26T08:00:00Z"); // 09:00 CET
        Instant winter = Instant.parse("2026-03-01T00:00:00Z");
        assertThat(slots(config, "2026-03-27T00:00:00Z", "2026-03-27T23:59:59Z", winter, List.of()).get(0))
                .isEqualTo("2026-03-27T08:00:00Z");
        assertThat(slots(config, "2026-03-30T00:00:00Z", "2026-03-30T23:59:59Z", winter, List.of()).get(0))
                .isEqualTo("2026-03-30T07:00:00Z");
    }

    @Test
    void skipsAWindowWhoseEdgeFallsIntoTheSpringForwardGap() {
        Fixtures.Props props = new Fixtures.Props();
        props.weekly.put("sun", List.of(new CalProperties.TimeWindow("02:30", "08:00"), new CalProperties.TimeWindow("10:00", "12:00")));
        // On 29 March 2026 the clock jumps from 02:00 to 03:00 in Berlin: 02:30 does not exist.
        assertThat(slots(props.build(), "2026-03-29T00:00:00Z", "2026-03-29T23:59:59Z", Instant.parse("2026-03-01T00:00:00Z"), List.of()))
                .containsExactly("2026-03-29T08:00:00Z");
    }

    @Test
    void hidesSlotsThatAreInThePastOrInsideTheMinimumNotice() {
        Instant noon = Instant.parse("2026-10-05T10:00:00Z"); // 12:00 Berlin
        assertThat(monday(Fixtures.config(), noon, List.of())).containsExactly("2026-10-05T11:00:00Z", "2026-10-05T13:00:00Z");
        CalConfig withNotice = new Fixtures.Props().eventType(Fixtures.eventType(120, null, 120, null, 0, 0)).build();
        assertThat(monday(withNotice, noon, List.of())).containsExactly("2026-10-05T13:00:00Z");
    }

    @Test
    void respectsTheBookingWindow() {
        CalConfig limited = new Fixtures.Props().eventType(Fixtures.eventType(120, null, 0, 2, 0, 0)).build();
        List<String> days = slots(limited, "2026-10-05T00:00:00Z", "2026-10-09T23:59:59Z", NOW, List.of()).stream()
                .map(start -> start.substring(0, 10)).distinct().toList();
        assertThat(days).containsExactly("2026-10-05", "2026-10-06");
    }

    @Test
    void removesSlotsThatTouchABusyIntervalAndOnlyThose() {
        CalConfig config = Fixtures.config();
        assertThat(monday(config, NOW, List.of(busy("2026-10-05T09:30:00Z", "2026-10-05T10:00:00Z")))).containsExactly(
                "2026-10-05T07:00:00Z", "2026-10-05T11:00:00Z", "2026-10-05T13:00:00Z");
        // Back-to-back is fine: intervals are half-open.
        assertThat(monday(config, NOW, List.of(busy("2026-10-05T09:00:00Z", "2026-10-05T11:00:00Z")))).containsExactly(
                "2026-10-05T07:00:00Z", "2026-10-05T11:00:00Z", "2026-10-05T13:00:00Z");
    }

    @Test
    void keepsBuffersFreeAroundBusyTimes() {
        CalConfig buffered = new Fixtures.Props().eventType(Fixtures.eventType(120, null, 0, null, 30, 30)).build();
        // 07:00 would end exactly at the busy start and 11:00 would start exactly at its end: both need the buffer.
        assertThat(monday(buffered, NOW, List.of(busy("2026-10-05T09:00:00Z", "2026-10-05T11:00:00Z"))))
                .containsExactly("2026-10-05T13:00:00Z");
        assertThat(SlotCalculator.blockRange(Instant.parse("2026-10-05T13:00:00Z"), buffered.eventTypes().get(0)))
                .isEqualTo(busy("2026-10-05T12:30:00Z", "2026-10-05T15:30:00Z"));
    }

    @Test
    void usesTheSlotIntervalAndNeverLetsASlotRunPastTheWindow() {
        CalConfig hourly = new Fixtures.Props().eventType(Fixtures.eventType(120, 60, 0, null, 0, 0)).build();
        List<String> starts = monday(hourly, NOW, List.of());
        assertThat(starts).hasSize(7); // 09:00 ... 15:00, the last one ends at 17:00
        assertThat(starts.get(6)).isEqualTo("2026-10-05T13:00:00Z");
    }

    @Test
    void letsADateOverrideReplaceOrBlockADay() {
        Fixtures.Props props = new Fixtures.Props();
        props.overrides.add(new CalProperties.DateOverride("2026-10-05", List.of()));
        props.overrides.add(new CalProperties.DateOverride("2026-10-06", List.of(new CalProperties.TimeWindow("14:00", "16:00"))));
        props.overrides.add(new CalProperties.DateOverride("2026-10-07", null));
        assertThat(slots(props.build(), "2026-10-05T00:00:00Z", "2026-10-07T23:59:59Z", NOW, List.of()))
                .containsExactly("2026-10-06T12:00:00Z");
    }

    @Test
    void treats2400AsMidnightOfTheNextDay() {
        Fixtures.Props props = new Fixtures.Props();
        props.weekly.put("mon", List.of(new CalProperties.TimeWindow("22:00", "24:00")));
        assertThat(monday(props.build(), NOW, List.of())).containsExactly("2026-10-05T20:00:00Z");
    }

    @Test
    void onlyReturnsSlotsThatStartInsideTheRequestedRange() {
        assertThat(slots(Fixtures.config(), "2026-10-05T09:00:00Z", "2026-10-05T11:00:00Z", NOW, List.of()))
                .containsExactly("2026-10-05T09:00:00Z", "2026-10-05T11:00:00Z");
    }

    @Test
    void acceptsExactSlotStartsAndRejectsEverythingInBetween() {
        CalConfig config = Fixtures.config();
        CalConfig.EventType eventType = config.eventTypes().get(0);
        assertThat(SlotCalculator.isBookableStart(Instant.parse("2026-10-05T09:00:00Z"), eventType, config, NOW, List.of())).isTrue();
        assertThat(SlotCalculator.isBookableStart(Instant.parse("2026-10-05T09:15:00Z"), eventType, config, NOW, List.of())).isFalse();
        assertThat(SlotCalculator.isBookableStart(Instant.parse("2026-10-05T19:00:00Z"), eventType, config, NOW, List.of())).isFalse();
        assertThat(SlotCalculator.isBookableStart(Instant.parse("2026-10-10T09:00:00Z"), eventType, config, NOW, List.of())).isFalse();
    }
}
