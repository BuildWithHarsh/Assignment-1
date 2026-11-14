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

    public static Resolution resolve(TimeExpression t, OffsetDateTime now) {
        if (t == null || t.kind() == null || t.kind() == TimeExpression.Kind.NONE) {
            return new Resolution.Absent();
        }
        if (t.kind() == TimeExpression.Kind.VAGUE) {
            return new Resolution.Vague();
        }
        if (t.kind() == TimeExpression.Kind.RELATIVE) {
            return relative(t, now);
        }
        return absolute(t, now);
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

    private static Resolution absolute(TimeExpression t, OffsetDateTime now) {
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
                    date = explicitDate(t, now);
                } catch (DateTimeException e) {
                    return new Resolution.Invalid("date");
                }
            }
            default -> {
                if (!t.hasClockTime()) {
                    return new Resolution.Absent();
                }
                date = now.toLocalDate();
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
        if (chosen == null && dateKind == TimeExpression.DateKind.NONE) {
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

    /** A stated year is used as given; a missing one resolves to the year that places the date in the future. */
    private static LocalDate explicitDate(TimeExpression t, OffsetDateTime now) {
        if (t.year() != null) {
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
