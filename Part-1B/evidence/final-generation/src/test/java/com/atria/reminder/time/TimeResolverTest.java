package com.atria.reminder.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // --- the model's own labels and values are evidence, not instructions ----------------------------------------

    private static TimeExpression parts(Kind kind, DateKind dateKind, Integer year, Integer month, Integer day,
                                        Integer hour, Meridiem meridiem) {
        return new TimeExpression(kind, null, null, dateKind, null, year, month, day, hour, null, meridiem);
    }

    private static String resolvedWithYear(TimeExpression t, OffsetDateTime now, boolean yearFromUser) {
        Resolution r = TimeResolver.resolve(t, now, yearFromUser);
        return assertInstanceOf(Resolution.Resolved.class, r,
                "expected a resolved instant but got " + r).at().toString();
    }

    @Test
    void aYearTheUserNeverStatedIsDiscardedRatherThanTrusted() {
        // "remind me to file the tax return on 3 January" - the model volunteers a training-era year.
        TimeExpression hallucinated = parts(Kind.ABSOLUTE, DateKind.EXPLICIT, 2023, 1, 3, null, null);
        assertEquals("2027-01-03T09:00+05:30", resolvedWithYear(hallucinated, NOW, false),
                "an unstated year is re-derived to the next future occurrence, crossing the year boundary");

        // "remind me to service the car on 2 December" - same month-boundary case, same year as --now.
        TimeExpression december = parts(Kind.ABSOLUTE, DateKind.EXPLICIT, 2023, 12, 2, null, null);
        assertEquals("2026-12-02T09:00+05:30", resolvedWithYear(december, NOW, false));
    }

    @Test
    void aYearTheUserDidStateIsHonouredSoAPastDateStaysPast() {
        // "remind me to renew the licence on 3 January 2023" - the user said the year, so FR-05 still applies.
        TimeExpression stated = parts(Kind.ABSOLUTE, DateKind.EXPLICIT, 2023, 1, 3, null, null);
        assertInstanceOf(Resolution.Past.class, TimeResolver.resolve(stated, NOW, true));
    }

    @Test
    void yearStatedInDetectsAYearInTheUsersOwnText() {
        assertTrue(TimeResolver.yearStatedIn("remind me to renew the licence on 3 January 2023", 2023));
        assertTrue(TimeResolver.yearStatedIn("book the hall for 12 June 2027", 2027));
        assertFalse(TimeResolver.yearStatedIn("remind me to file the tax return on 3 January", 2023),
                "no year in the request means the extracted year is a guess");
        assertFalse(TimeResolver.yearStatedIn("remind me to call Raj at 7", null));
        assertFalse(TimeResolver.yearStatedIn(null, 2023));
        assertFalse(TimeResolver.yearStatedIn("parcel 4921 arrives on 2 December", 2023),
                "a short number is not a year");
    }

    @Test
    void aDateWithASpacedMeridiemTimeAndAnUnstatedYearStillResolves() {
        // "remind me to post the forms on 28 December at 9 am"
        TimeExpression t = parts(Kind.ABSOLUTE, DateKind.EXPLICIT, 2023, 12, 28, 9, Meridiem.AM);
        assertEquals("2026-12-28T09:00+05:30", resolvedWithYear(t, NOW, false));
    }

    @Test
    void aKindOfNoneIsOverriddenByThePartsThatAreActuallyPresent() {
        // "remind me to water the plants at 6" - the model labels the time "none" but reports hour 6 and "today".
        TimeExpression mislabelled = parts(Kind.NONE, DateKind.TODAY, null, null, null, 6, null);
        assertEquals("2026-11-14T18:00+05:30", resolved(mislabelled, NOW),
                "FR-04 still applies: the next future 6 o'clock after 10:00 is 18:00");

        // The same mislabelling with a day and a clock time.
        TimeExpression withDay = parts(Kind.NONE, DateKind.TOMORROW, null, null, null, 3, Meridiem.PM);
        assertEquals("2026-11-15T15:00+05:30", resolved(withDay, NOW));

        // And with a calendar date but no clock time, so FR-03's 09:00 default applies.
        TimeExpression dateOnly = parts(Kind.NONE, DateKind.NONE, null, 12, 2, null, null);
        assertEquals("2026-12-02T09:00+05:30", resolved(dateOnly, NOW));
    }

    @Test
    void anAmbiguousBareHourLabelledTodayRollsForwardWhenBothReadingsHavePassed() {
        // "remind me to water the plants at 6" asked at 20:00: 06:00 and 18:00 have both gone.
        OffsetDateTime night = OffsetDateTime.parse("2026-11-14T20:00:00+05:30");
        TimeExpression bareHour = parts(Kind.NONE, DateKind.TODAY, null, null, null, 6, null);
        assertEquals("2026-11-15T06:00+05:30", resolved(bareHour, night));
    }

    @Test
    void anExplicitMeridiemLabelledTodayStaysAPastTime() {
        // "remind me to water the plants today at 6am" asked at 20:00 has one reading, and it has passed.
        OffsetDateTime night = OffsetDateTime.parse("2026-11-14T20:00:00+05:30");
        TimeExpression sixAm = parts(Kind.NONE, DateKind.TODAY, null, null, null, 6, Meridiem.AM);
        assertInstanceOf(Resolution.Past.class, TimeResolver.resolve(sixAm, night));
    }

    @Test
    void meridiemInReadsTheAmPmTheUserActuallyWrote() {
        assertEquals(Meridiem.AM, TimeResolver.meridiemIn("remind me to stretch today at 9am"));
        assertEquals(Meridiem.AM, TimeResolver.meridiemIn("remind me to post the forms at 9 a.m."));
        assertEquals(Meridiem.PM, TimeResolver.meridiemIn("remind me to call Raj at 7:30 pm"));
        assertNull(TimeResolver.meridiemIn("remind me to call Raj at 7"), "no am/pm written");
        assertNull(TimeResolver.meridiemIn("I am going to the dentist tomorrow"),
                "a bare 'am' not attached to an hour is the verb, not a meridiem");
        assertNull(TimeResolver.meridiemIn("remind me between 9am and 5pm"), "both written: no single reading");
        assertNull(TimeResolver.meridiemIn(null));
    }

    @Test
    void aStatedMeridiemKeepsAPastTimePast() {
        // "remind me to stretch today at 9am" asked at 10:00. With the am restored there is one reading, and it
        // has passed, so FR-05 applies instead of FR-04 picking 21:00.
        TimeExpression dropped = parts(Kind.ABSOLUTE, DateKind.TODAY, null, null, null, 9, null);
        assertEquals("2026-11-14T21:00+05:30", resolved(dropped, NOW),
                "without the meridiem the bare-hour rule gives the evening reading");

        TimeExpression restored = dropped.withMeridiem(TimeResolver.meridiemIn("remind me to stretch today at 9am"));
        assertInstanceOf(Resolution.Past.class, TimeResolver.resolve(restored, NOW),
                "with the am restored it is a past time");
    }

    @Test
    void aTimeWithNoPartsAtAllIsStillAbsent() {
        assertInstanceOf(Resolution.Absent.class, TimeResolver.resolve(
                parts(Kind.NONE, DateKind.NONE, null, null, null, null, null), NOW));
        assertInstanceOf(Resolution.Vague.class, TimeResolver.resolve(
                parts(Kind.VAGUE, DateKind.NONE, null, null, null, null, null), NOW));
    }

    // --- FR-07 beats FR-03: a window is not a calendar day -------------------------------------------------------

    private static Resolution fromText(TimeExpression t, String input) {
        return TimeResolver.resolve(t, NOW, false, input);
    }

    private static String resolvedFromText(TimeExpression t, String input) {
        Resolution r = fromText(t, input);
        return assertInstanceOf(Resolution.Resolved.class, r,
                "expected a resolved instant but got " + r).at().toString();
    }

    @Test
    void aWeekLevelWindowStaysVagueEvenWhenTheModelInventsADay() {
        String request = "remind me to book the car service sometime next week";
        assertInstanceOf(Resolution.Vague.class,
                fromText(absolute(DateKind.EXPLICIT, null, null, 11, 20, null, null, null), request),
                "an invented calendar date inside the window is a guess, not a day the user named");
        assertInstanceOf(Resolution.Vague.class,
                fromText(absolute(DateKind.WEEKDAY, Weekday.FRIDAY, null, null, null, null, null, null), request),
                "an invented weekday inside the window is a guess too");
    }

    @Test
    void monthLevelAndUnanchoredWindowsAreVagueToo() {
        assertInstanceOf(Resolution.Vague.class, fromText(
                absolute(DateKind.EXPLICIT, null, null, 12, 3, null, null, null),
                "remind me to top up the metro card next month"));
        assertInstanceOf(Resolution.Vague.class, fromText(
                absolute(DateKind.EXPLICIT, null, null, 11, 19, null, null, null),
                "ping me about the electricity bill later this week"));
        assertInstanceOf(Resolution.Vague.class, fromText(
                absolute(DateKind.TOMORROW, null, null, null, null, null, null, null),
                "remind me to clean the fish tank soon"));
        assertInstanceOf(Resolution.Vague.class, fromText(
                absolute(DateKind.EXPLICIT, null, null, 11, 25, null, null, null),
                "remind me to chase the landlord in the coming weeks"));
        assertInstanceOf(Resolution.Vague.class, fromText(
                absolute(DateKind.WEEKDAY, Weekday.WEDNESDAY, null, null, null, null, null, null),
                "remind me to sort the garage out at some point"));
    }

    @Test
    void aClockTimeInsideAWindowStillNamesNoDay() {
        assertInstanceOf(Resolution.Vague.class, fromText(
                absolute(DateKind.EXPLICIT, null, null, 11, 20, 3, null, Meridiem.PM),
                "remind me to collect the parcel next week at 3pm"));
    }

    @Test
    void aDayNamedInsideAWindowStillResolves() {
        assertEquals("2026-11-17T09:00+05:30", resolvedFromText(
                absolute(DateKind.WEEKDAY, Weekday.TUESDAY, null, null, null, null, null, null),
                "remind me to book the car service next week on Tuesday"));
        assertEquals("2026-12-01T09:00+05:30", resolvedFromText(
                absolute(DateKind.EXPLICIT, null, null, 12, 1, null, null, null),
                "remind me to pay the rent on the 1st of next month"));
        assertEquals("2026-11-14T12:00+05:30", resolvedFromText(
                absolute(DateKind.NONE, null, null, null, null, 12, null, null),
                "remind me to eat something later today at 12"));
        assertEquals("2026-11-14T12:00+05:30", resolvedFromText(relative(2, Unit.HOUR),
                "remind me to stretch soon, in 2 hours"));
        assertEquals("2026-12-09T09:00+05:30", resolvedFromText(
                absolute(DateKind.EXPLICIT, null, null, 12, 9, null, null, null),
                "remind me to renew the gym membership on 9 December"));
    }

    @Test
    void windowOnlyInReadsTheUsersOwnWords() {
        assertTrue(TimeResolver.windowOnlyIn("remind me to book the car service sometime next week"));
        assertTrue(TimeResolver.windowOnlyIn("remind me to file the insurance claim later this month"));
        assertTrue(TimeResolver.windowOnlyIn("remind me to call the plumber at some point"));
        assertTrue(TimeResolver.windowOnlyIn("move the standup reminder to sometime next week"));
        assertFalse(TimeResolver.windowOnlyIn("remind me to call the plumber tomorrow at 9am"));
        assertFalse(TimeResolver.windowOnlyIn("remind me to submit the form on 12 March"));
        assertFalse(TimeResolver.windowOnlyIn("remind me in 45 minutes to take the bread out"));
        assertFalse(TimeResolver.windowOnlyIn("remind me to join the call at 7"));
        assertFalse(TimeResolver.windowOnlyIn("remind me to water the plants next Tuesday"));
        assertFalse(TimeResolver.windowOnlyIn(""));
        assertFalse(TimeResolver.windowOnlyIn(null));
    }

    @Test
    void theWindowRuleLeavesEveryOtherResolutionAlone() {
        // No window in the text, so the parts decide exactly as before.
        assertEquals("2026-11-15T15:00+05:30", resolvedFromText(
                absolute(DateKind.TOMORROW, null, null, null, null, 3, null, Meridiem.PM),
                "remind me to call the dentist tomorrow at 3pm"));
        assertInstanceOf(Resolution.Invalid.class, fromText(
                absolute(DateKind.EXPLICIT, null, null, 11, 31, null, null, null),
                "remind me about the dentist on 31 November"), "FR-06 still reports an impossible date");
        assertInstanceOf(Resolution.Absent.class, fromText(TimeExpression.none(),
                "remind me to buy milk"));
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
