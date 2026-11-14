package com.atria.reminder.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.atria.reminder.model.TimeExpression;
import com.atria.reminder.model.TimeExpression.DateKind;
import com.atria.reminder.model.TimeExpression.Kind;
import com.atria.reminder.model.TimeExpression.Meridiem;
import com.atria.reminder.model.TimeExpression.Unit;
import com.atria.reminder.model.TimeExpression.Weekday;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class TimeResolverTest {

    /** 2026-11-14 is a Saturday. */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-14T10:00:00+05:30");

    private static TimeExpression relative(int amount, Unit unit) {
        return new TimeExpression(Kind.RELATIVE, amount, unit, DateKind.NONE, null,
                null, null, null, null, null, null);
    }

    private static TimeExpression absolute(DateKind dateKind, Weekday weekday, Integer year, Integer month,
                                           Integer day, Integer hour, Integer minute, Meridiem meridiem) {
        return new TimeExpression(Kind.ABSOLUTE, null, null, dateKind, weekday, year, month, day, hour, minute,
                meridiem);
    }

    private static String resolved(TimeExpression t, OffsetDateTime now) {
        Resolution r = TimeResolver.resolve(t, now);
        return assertInstanceOf(Resolution.Resolved.class, r,
                "expected a resolved instant but got " + r).at().toString();
    }

    // --- FR-01 relative ------------------------------------------------------------------------------------------

    @Test
    void inFortyFiveMinutes() {
        assertEquals("2026-11-14T10:45+05:30", resolved(relative(45, Unit.MINUTE), NOW));
    }

    @Test
    void inThreeHoursCrossesMidnight() {
        OffsetDateTime lateNow = OffsetDateTime.parse("2026-11-14T23:00:00+05:30");
        assertEquals("2026-11-15T02:00+05:30", resolved(relative(3, Unit.HOUR), lateNow));
    }

    @Test
    void inTwoDaysAndOneWeek() {
        assertEquals("2026-11-16T10:00+05:30", resolved(relative(2, Unit.DAY), NOW));
        assertEquals("2026-11-21T10:00+05:30", resolved(relative(1, Unit.WEEK), NOW));
    }

    @Test
    void todayAndTomorrowWithAClockTime() {
        assertEquals("2026-11-14T20:00+05:30",
                resolved(absolute(DateKind.TODAY, null, null, null, null, 8, null, Meridiem.PM), NOW));
        assertEquals("2026-11-15T15:00+05:30",
                resolved(absolute(DateKind.TOMORROW, null, null, null, null, 3, null, Meridiem.PM), NOW));
    }

    // --- FR-02 weekdays ------------------------------------------------------------------------------------------

    @Test
    void aWeekdayIsTheFirstOneStrictlyAfterToday() {
        assertEquals("2026-11-17T09:00+05:30",
                resolved(absolute(DateKind.WEEKDAY, Weekday.TUESDAY, null, null, null, null, null, null), NOW));
        assertEquals("2026-11-20T16:00+05:30",
                resolved(absolute(DateKind.WEEKDAY, Weekday.FRIDAY, null, null, null, 4, null, Meridiem.PM), NOW));
    }

    @Test
    void todaysOwnWeekdayMeansNextWeek() {
        // NOW is a Saturday, so "Saturday" is seven days later, never today.
        assertEquals("2026-11-21T09:00+05:30",
                resolved(absolute(DateKind.WEEKDAY, Weekday.SATURDAY, null, null, null, null, null, null), NOW));
    }

    // --- FR-03 bare date -----------------------------------------------------------------------------------------

    @Test
    void aDateWithNoClockTimeMeansNineInTheMorning() {
        assertEquals("2026-12-25T09:00+05:30",
                resolved(absolute(DateKind.EXPLICIT, null, null, 12, 25, null, null, null), NOW));
        assertEquals("2026-11-15T09:00+05:30",
                resolved(absolute(DateKind.TOMORROW, null, null, null, null, null, null, null), NOW));
    }

    @Test
    void aMissingYearResolvesToTheNextFutureOccurrence() {
        // 1 March has already passed in 2026, so it means 2027.
        assertEquals("2027-03-01T09:00+05:30",
                resolved(absolute(DateKind.EXPLICIT, null, null, 3, 1, null, null, null), NOW));
    }

    // --- FR-04 bare hour -----------------------------------------------------------------------------------------

    @Test
    void bareHourSevenAtTenInTheMorningMeansSevenInTheEvening() {
        assertEquals("2026-11-14T19:00+05:30",
                resolved(absolute(DateKind.NONE, null, null, null, null, 7, null, null), NOW));
    }

    @Test
    void bareHourSevenAtEightInTheEveningMeansSevenTomorrowMorning() {
        OffsetDateTime eveningNow = OffsetDateTime.parse("2026-11-14T20:00:00+05:30");
        assertEquals("2026-11-15T07:00+05:30",
                resolved(absolute(DateKind.NONE, null, null, null, null, 7, null, null), eveningNow));
    }

    @Test
    void bareHourTwelveIsNoonOrMidnight() {
        assertEquals("2026-11-14T12:00+05:30",
                resolved(absolute(DateKind.NONE, null, null, null, null, 12, null, null), NOW));
        OffsetDateTime afternoon = OffsetDateTime.parse("2026-11-14T13:00:00+05:30");
        assertEquals("2026-11-15T00:00+05:30",
                resolved(absolute(DateKind.NONE, null, null, null, null, 12, null, null), afternoon));
    }

    @Test
    void anHourOfThirteenOrMoreIsAlreadyUnambiguous() {
        assertEquals("2026-11-14T19:00+05:30",
                resolved(absolute(DateKind.NONE, null, null, null, null, 19, null, null), NOW));
        OffsetDateTime night = OffsetDateTime.parse("2026-11-14T20:00:00+05:30");
        assertEquals("2026-11-15T19:00+05:30",
                resolved(absolute(DateKind.NONE, null, null, null, null, 19, null, null), night));
    }

    @Test
    void anExplicitMeridiemSettlesTheHourOutright() {
        assertEquals("2026-11-15T07:00+05:30",
                resolved(absolute(DateKind.TOMORROW, null, null, null, null, 7, null, Meridiem.AM), NOW));
        assertEquals("2026-11-14T23:30+05:30",
                resolved(absolute(DateKind.TODAY, null, null, null, null, 11, 30, Meridiem.PM), NOW));
        assertEquals("2026-11-15T00:00+05:30",
                resolved(absolute(DateKind.TOMORROW, null, null, null, null, 12, null, Meridiem.AM), NOW));
        assertEquals("2026-11-15T12:00+05:30",
                resolved(absolute(DateKind.TOMORROW, null, null, null, null, 12, null, Meridiem.PM), NOW));
    }

    @Test
    void anHourAlreadyInTwentyFourHourFormIgnoresARedundantMeridiem() {
        // Models commonly report "3pm" as hour=15 plus meridiem=pm; that is 15:00, not an impossible time.
        assertEquals("2026-11-15T15:00+05:30",
                resolved(absolute(DateKind.TOMORROW, null, null, null, null, 15, null, Meridiem.PM), NOW));
        assertEquals("2026-11-14T19:00+05:30",
                resolved(absolute(DateKind.TODAY, null, null, null, null, 19, null, Meridiem.PM), NOW));
    }

    // --- FR-05 past ----------------------------------------------------------------------------------------------

    @Test
    void aTimeAlreadyPastTodayIsNotSilentlyMovedToTomorrow() {
        Resolution r = TimeResolver.resolve(
                absolute(DateKind.TODAY, null, null, null, null, 9, null, Meridiem.AM), NOW);
        assertInstanceOf(Resolution.Past.class, r);
    }

    @Test
    void anInstantEqualToNowIsNotStrictlyAfterIt() {
        Resolution r = TimeResolver.resolve(relative(0, Unit.MINUTE), NOW);
        assertInstanceOf(Resolution.Past.class, r);
    }

    // --- FR-06 impossible ----------------------------------------------------------------------------------------

    @Test
    void impossibleDatesAndTimes() {
        assertInstanceOf(Resolution.Invalid.class, TimeResolver.resolve(
                absolute(DateKind.EXPLICIT, null, null, 11, 31, null, null, null), NOW), "31 November");
        assertInstanceOf(Resolution.Invalid.class, TimeResolver.resolve(
                absolute(DateKind.EXPLICIT, null, 2027, 2, 30, null, null, null), NOW), "30 February");
        assertInstanceOf(Resolution.Invalid.class, TimeResolver.resolve(
                absolute(DateKind.TOMORROW, null, null, null, null, 25, 0, null), NOW), "25:00");
        assertInstanceOf(Resolution.Invalid.class, TimeResolver.resolve(
                absolute(DateKind.TOMORROW, null, null, null, null, 9, 75, null), NOW), "minute 75");
        assertInstanceOf(Resolution.Invalid.class, TimeResolver.resolve(
                absolute(DateKind.TOMORROW, null, null, null, null, 24, 0, null), NOW), "24:00");
    }

    // --- INV-07 horizon ------------------------------------------------------------------------------------------

    @Test
    void theHorizonBoundaryIsThreeHundredAndSixtySixDays() {
        assertInstanceOf(Resolution.Resolved.class, TimeResolver.resolve(relative(366, Unit.DAY), NOW));
        assertInstanceOf(Resolution.OutOfHorizon.class, TimeResolver.resolve(relative(367, Unit.DAY), NOW));
        assertInstanceOf(Resolution.OutOfHorizon.class, TimeResolver.resolve(
                absolute(DateKind.EXPLICIT, null, 2028, 1, 1, null, null, null), NOW));
    }

    // --- offsets -------------------------------------------------------------------------------------------------

    @Test
    void theOffsetFromNowIsRetained() {
        OffsetDateTime pacific = OffsetDateTime.parse("2026-11-14T10:00:00-08:00");
        assertEquals("2026-11-15T15:00-08:00",
                resolved(absolute(DateKind.TOMORROW, null, null, null, null, 3, null, Meridiem.PM), pacific));
        OffsetDateTime utc = OffsetDateTime.parse("2026-11-14T10:00:00Z");
        assertEquals("2026-11-15T15:00Z",
                resolved(absolute(DateKind.TOMORROW, null, null, null, null, 3, null, Meridiem.PM), utc));
    }

    // --- FR-07 vague and absent ----------------------------------------------------------------------------------

    @Test
    void vagueAndAbsentTimes() {
        assertInstanceOf(Resolution.Vague.class, TimeResolver.resolve(
                new TimeExpression(Kind.VAGUE, null, null, DateKind.NONE, null, null, null, null, null, null, null),
                NOW));
        assertInstanceOf(Resolution.Absent.class, TimeResolver.resolve(TimeExpression.none(), NOW));
        assertInstanceOf(Resolution.Absent.class, TimeResolver.resolve(null, NOW));
        assertInstanceOf(Resolution.Absent.class, TimeResolver.resolve(
                absolute(DateKind.NONE, null, null, null, null, null, null, null), NOW),
                "no day and no clock time is absent, not 09:00 today");
    }

    @Test
    void incompletePartsAreVagueRatherThanGuessed() {
        assertInstanceOf(Resolution.Vague.class, TimeResolver.resolve(
                absolute(DateKind.EXPLICIT, null, null, null, null, null, null, null), NOW), "no month or day");
        assertInstanceOf(Resolution.Vague.class, TimeResolver.resolve(
                absolute(DateKind.WEEKDAY, null, null, null, null, null, null, null), NOW), "no weekday named");
        assertInstanceOf(Resolution.Vague.class, TimeResolver.resolve(
                new TimeExpression(Kind.RELATIVE, null, null, DateKind.NONE, null, null, null, null, null, null, null),
                NOW), "no amount or unit");
    }
}
