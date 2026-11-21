package com.atria.reminder.time;

import com.atria.reminder.model.TimeExpression;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FR-01..FR-06 and the INV-07 horizon, computed deterministically from the model's time parts and --now.
 *
 * <p>All arithmetic is on OffsetDateTime in the offset carried by --now. The system clock is never read here, so
 * the same parts and the same --now always produce the same instant.
 */
public final class TimeResolver {

    public static final int HORIZON_DAYS = 366;

    private TimeResolver() {
    }

    /** Resolves treating any year in the expression as one the user actually stated. */
    public static Resolution resolve(TimeExpression t, OffsetDateTime now) {
        return resolve(t, now, true);
    }

    /**
     * Resolves against the user's own words as well as the model's parts.
     *
     * <p>FR-03's 09:00 default turns a <em>calendar day</em> into an instant. A week- or month-level window is not
     * a calendar day, so when the user's text names only such a window, the day parts the model filled in are its
     * guess at which day inside the window was meant, and the time is vague however confident those parts look
     * (FR-07, constitution IV). Decided from the user's text rather than from {@link TimeExpression#kind()},
     * because the label is the model's and the text is not.
     *
     * @param userInput the request exactly as the person wrote it, or null to resolve from the parts alone.
     */
    public static Resolution resolve(TimeExpression t, OffsetDateTime now, boolean yearFromUser, String userInput) {
        if (windowOnlyIn(userInput)) {
            return new Resolution.Vague();
        }
        return resolve(t, now, yearFromUser);
    }

    /**
     * True when the user's own text pins the request to a window wider than a day — a week- or month-level window,
     * or a wholly unanchored one — and names no calendar day inside it.
     *
     * <p>Any day marker in the text cancels the window, because a day inside a window is still one day. A clock
     * time does not cancel it: a week-level window with an hour attached still never says which day.
     */
    public static boolean windowOnlyIn(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        if (DAY_PINNED_IN_TEXT.matcher(input).find()) {
            return false;
        }
        return WINDOW_IN_TEXT.matcher(input).find();
    }

    private static final String MONTH_NAME = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*";

    /** A window wider than one calendar day: week-level, month-level or wholly unanchored. */
    private static final java.util.regex.Pattern WINDOW_IN_TEXT = java.util.regex.Pattern.compile(
            "\\b(?:next|this|coming|the\\s+coming|following|upcoming)\\s+"
                    + "(?:week|month|quarter|year|fortnight)s?\\b"
            + "|\\bin\\s+(?:the\\s+)?(?:next|coming|following)\\s+(?:few\\s+|couple\\s+of\\s+)?"
                    + "(?:days|weeks|months|years)\\b"
            + "|\\b(?:sometime|some\\s+time|someday|some\\s+day|soon|later|eventually|whenever"
                    + "|at\\s+some\\s+point|one\\s+of\\s+these\\s+days|in\\s+the\\s+future|in\\s+due\\s+course"
                    + "|when\\s+i\\s+(?:get|have)\\s+(?:a\\s+chance|time|the\\s+time))\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** A specific calendar day, or a counted offset from now, somewhere in the user's text. */
    private static final java.util.regex.Pattern DAY_PINNED_IN_TEXT = java.util.regex.Pattern.compile(
            "\\b(?:today|tonight|tomorrow|tmr|tmrw|day\\s+after\\s+tomorrow"
                    + "|this\\s+(?:morning|afternoon|evening|night)"
                    + "|monday|tuesday|wednesday|thursday|friday|saturday|sunday"
                    + "|mon|tue|tues|wed|weds|thu|thur|thurs|fri)\\b"
            + "|\\b\\d{1,2}\\s*(?:st|nd|rd|th)\\b"
            + "|\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b"
            + "|\\b\\d{1,2}\\s*[/-]\\s*\\d{1,2}\\b"
            + "|\\b\\d{1,2}\\s*(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH_NAME + "\\b"
            + "|\\b" + MONTH_NAME + "\\s+\\d{1,2}\\b"
            + "|\\bin\\s+(?:\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|half\\s+an?)\\s+"
                    + "(?:and\\s+a\\s+half\\s+)?(?:min|mins|minute|minutes|hr|hrs|hour|hours|day|days"
                    + "|week|weeks|month|months)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * @param yearFromUser whether the user's own text contained a year. A year the user never said is a guess by
     *                     the model, so it is discarded and re-derived rather than trusted (constitution IV).
     */
    public static Resolution resolve(TimeExpression t, OffsetDateTime now, boolean yearFromUser) {
        if (t == null) {
            return new Resolution.Absent();
        }
        boolean hasRelativeParts = t.relativeAmount() != null && t.relativeUnit() != null;
        boolean hasAbsoluteParts = (t.dateKind() != null && t.dateKind() != TimeExpression.DateKind.NONE)
                || t.hour() != null || t.month() != null || t.day() != null;

        // 'kind' is only the model's label for what it found; the parts are the evidence. A label of "none"
        // alongside a day or a clock time is a mislabelling, not an absent time, so the parts decide.
        if (!hasRelativeParts && !hasAbsoluteParts) {
            boolean referredToATime = t.kind() == TimeExpression.Kind.VAGUE
                    || t.kind() == TimeExpression.Kind.RELATIVE;
            return referredToATime ? new Resolution.Vague() : new Resolution.Absent();
        }
        if (hasRelativeParts && !hasAbsoluteParts) {
            return relative(t, now);
        }
        return absolute(t, now, yearFromUser);
    }

    /**
     * True when the user's own text contains a year, so a year in the extraction can be trusted. A four-digit
     * year anywhere in the request counts, which covers wordings the model may normalise.
     */
    public static boolean yearStatedIn(String input, Integer extractedYear) {
        if (input == null || extractedYear == null) {
            return false;
        }
        return input.contains(Integer.toString(extractedYear)) || YEAR_IN_TEXT.matcher(input).find();
    }

    private static final java.util.regex.Pattern YEAR_IN_TEXT =
            java.util.regex.Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");

    /** An am/pm attached to a clock hour, e.g. "9am", "9 a.m.", "7:30 pm". */
    private static final java.util.regex.Pattern MERIDIEM_IN_TEXT = java.util.regex.Pattern.compile(
            "(?<!\\d)\\d{1,2}(?::\\d{2})?\\s*([ap])\\.?m\\.?\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The am/pm the user actually wrote next to a clock hour, or null when they wrote none — or wrote both, which
     * no single hour can satisfy.
     *
     * <p>Models drop a stated meridiem often enough that trusting its absence would turn "today at 9am" into the
     * next 9 o'clock in the evening, when FR-05 requires a past time to be reported as such.
     */
    public static TimeExpression.Meridiem meridiemIn(String input) {
        if (input == null) {
            return null;
        }
        var m = MERIDIEM_IN_TEXT.matcher(input);
        TimeExpression.Meridiem found = null;
        while (m.find()) {
            TimeExpression.Meridiem here = m.group(1).equalsIgnoreCase("a")
                    ? TimeExpression.Meridiem.AM
                    : TimeExpression.Meridiem.PM;
            if (found != null && found != here) {
                return null;
            }
            found = here;
        }
        return found;
    }

    /** FR-01: "in N minutes/hours/days/weeks" counted from --now. */
    private static Resolution relative(TimeExpression t, OffsetDateTime now) {
        if (t.relativeAmount() == null || t.relativeUnit() == null) {
            return new Resolution.Vague();
        }
        long amount = t.relativeAmount();
        OffsetDateTime at;
        try {
            at = switch (t.relativeUnit()) {
                case MINUTE -> now.plusMinutes(amount);
                case HOUR -> now.plusHours(amount);
                case DAY -> now.plusDays(amount);
                case WEEK -> now.plusWeeks(amount);
            };
        } catch (DateTimeException | ArithmeticException e) {
            return new Resolution.Invalid("relative amount");
        }
        return classify(at, now);
    }

    private static Resolution absolute(TimeExpression t, OffsetDateTime now, boolean yearFromUser) {
        // --- the day -------------------------------------------------------------------------------------------
        TimeExpression.DateKind dateKind = t.dateKind() == null ? TimeExpression.DateKind.NONE : t.dateKind();
        LocalDate date;
        switch (dateKind) {
            case TODAY -> date = now.toLocalDate();
            case TOMORROW -> date = now.toLocalDate().plusDays(1);
            case WEEKDAY -> {
                if (t.weekday() == null) {
                    return new Resolution.Vague();
                }
                date = nextWeekday(now.toLocalDate(), DayOfWeek.valueOf(t.weekday().name()));
            }
            case EXPLICIT -> {
                if (t.month() == null || t.day() == null) {
                    return new Resolution.Vague();
                }
                try {
                    date = explicitDate(t, now, yearFromUser);
                } catch (DateTimeException e) {
                    return new Resolution.Invalid("date");
                }
            }
            default -> {
                if (t.month() != null && t.day() != null) {
                    // A calendar date was reported even though date_kind says otherwise; the parts decide.
                    try {
                        date = explicitDate(t, now, yearFromUser);
                    } catch (DateTimeException e) {
                        return new Resolution.Invalid("date");
                    }
                    dateKind = TimeExpression.DateKind.EXPLICIT;
                } else if (!t.hasClockTime()) {
                    return new Resolution.Absent();
                } else {
                    date = now.toLocalDate();
                }
            }
        }

        // --- the clock time ------------------------------------------------------------------------------------
        int minute = t.minute() == null ? 0 : t.minute();
        if (minute < 0 || minute > 59) {
            return new Resolution.Invalid("minute");
        }

        if (!t.hasClockTime()) {
            // FR-03: a date with no clock time means 09:00.
            OffsetDateTime at = at(date, LocalTime.of(9, 0), now);
            return classify(at, now);
        }

        int hour = t.hour();
        if (hour < 0 || hour > 23) {
            return new Resolution.Invalid("hour");
        }

        List<Integer> hours = candidateHours(hour, t.meridiem());
        if (hours.isEmpty()) {
            return new Resolution.Invalid("hour");
        }

        List<OffsetDateTime> candidates = new ArrayList<>();
        for (int h : hours) {
            candidates.add(at(date, LocalTime.of(h, minute), now));
        }
        // FR-04: the earliest candidate strictly after --now wins.
        OffsetDateTime chosen = earliestAfter(candidates, now);
        // An ambiguous bare hour labelled "today" is not really pinned to a day: if both readings have already
        // passed, the user cannot have meant today, and FR-04's next-future-occurrence rule still applies. An hour
        // with an explicit am/pm has one reading, so it stays a past time (FR-05).
        boolean dayNotPinned = dateKind == TimeExpression.DateKind.NONE
                || (dateKind == TimeExpression.DateKind.TODAY && hours.size() > 1);
        if (chosen == null && dayNotPinned) {
            // No day was given, so a bare hour rolls to the next day rather than being a past time.
            List<OffsetDateTime> tomorrow = new ArrayList<>();
            for (int h : hours) {
                tomorrow.add(at(date.plusDays(1), LocalTime.of(h, minute), now));
            }
            chosen = earliestAfter(tomorrow, now);
        }
        if (chosen == null) {
            // A day was given and every reading of it is in the past (FR-05).
            OffsetDateTime latest = candidates.get(0);
            for (OffsetDateTime c : candidates) {
                if (c.isAfter(latest)) {
                    latest = c;
                }
            }
            return new Resolution.Past(latest);
        }
        return classify(chosen, now);
    }

    /**
     * FR-04 candidate hours. An hour of 1..11 could be morning or evening; 12 and 0 could be noon or midnight; an
     * hour of 13..23 is already unambiguous. An explicit am/pm settles it outright.
     */
    private static List<Integer> candidateHours(int hour, TimeExpression.Meridiem meridiem) {
        if (meridiem != null) {
            if (hour >= 13) {
                // Already in 24-hour form, so the meridiem adds nothing: "15 pm" is 15:00.
                return List.of(hour);
            }
            if (hour == 0) {
                return List.of(meridiem == TimeExpression.Meridiem.PM ? 12 : 0);
            }
            int base = hour % 12;
            return List.of(meridiem == TimeExpression.Meridiem.PM ? base + 12 : base);
        }
        if (hour >= 13) {
            return List.of(hour);
        }
        if (hour == 0 || hour == 12) {
            return List.of(12, 0);
        }
        return List.of(hour, hour + 12);
    }

    /** FR-02: the first such weekday strictly after the --now date, so "Tuesday" on a Tuesday means next week. */
    private static LocalDate nextWeekday(LocalDate today, DayOfWeek target) {
        LocalDate d = today.plusDays(1);
        while (d.getDayOfWeek() != target) {
            d = d.plusDays(1);
        }
        return d;
    }

    /**
     * A year the user stated is used as given, so "20 November 2023" is still a past time (FR-05). A year the user
     * never stated is a guess by the model — commonly a training-era year such as 2023 — and is discarded, so a
     * bare "20 November" resolves to the next future occurrence exactly as a missing year does.
     */
    private static LocalDate explicitDate(TimeExpression t, OffsetDateTime now, boolean yearFromUser) {
        if (t.year() != null && yearFromUser) {
            return LocalDate.of(t.year(), t.month(), t.day());
        }
        LocalDate candidate = LocalDate.of(now.getYear(), t.month(), t.day());
        if (candidate.isBefore(now.toLocalDate())) {
            return LocalDate.of(now.getYear() + 1, t.month(), t.day());
        }
        return candidate;
    }

    private static OffsetDateTime at(LocalDate date, LocalTime time, OffsetDateTime now) {
        return OffsetDateTime.of(date, time, now.getOffset());
    }

    private static OffsetDateTime earliestAfter(List<OffsetDateTime> candidates, OffsetDateTime now) {
        OffsetDateTime best = null;
        for (OffsetDateTime c : candidates) {
            if (c.isAfter(now) && (best == null || c.isBefore(best))) {
                best = c;
            }
        }
        return best;
    }

    private static Resolution classify(OffsetDateTime at, OffsetDateTime now) {
        if (!at.isAfter(now)) {
            return new Resolution.Past(at);
        }
        if (at.isAfter(now.plusDays(HORIZON_DAYS))) {
            return new Resolution.OutOfHorizon(at);
        }
        return new Resolution.Resolved(at);
    }
}
