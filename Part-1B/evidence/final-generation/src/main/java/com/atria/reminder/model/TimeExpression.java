package com.atria.reminder.model;

/**
 * The time parts the model reports, mirroring the {@code time} object of contracts/extraction-schema.json.
 *
 * <p>Untrusted and deliberately uncorrected: out-of-range values such as month 11 day 31, or hour 25, must survive
 * this far so TimeResolver can reject them as INVALID_TIME (FR-06). The model never supplies a timestamp.
 */
public record TimeExpression(
        Kind kind,
        Integer relativeAmount,
        Unit relativeUnit,
        DateKind dateKind,
        Weekday weekday,
        Integer year,
        Integer month,
        Integer day,
        Integer hour,
        Integer minute,
        Meridiem meridiem) {

    public enum Kind { NONE, VAGUE, RELATIVE, ABSOLUTE }

    public enum Unit { MINUTE, HOUR, DAY, WEEK }

    public enum DateKind { NONE, TODAY, TOMORROW, WEEKDAY, EXPLICIT }

    public enum Weekday { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

    public enum Meridiem { AM, PM }

    public static TimeExpression none() {
        return new TimeExpression(Kind.NONE, null, null, DateKind.NONE, null, null, null, null, null, null, null);
    }

    public boolean hasClockTime() {
        return hour != null;
    }

    /** The same expression with the meridiem replaced, used to restore an am/pm the model dropped. */
    public TimeExpression withMeridiem(Meridiem replacement) {
        return new TimeExpression(kind, relativeAmount, relativeUnit, dateKind, weekday, year, month, day, hour,
                minute, replacement);
    }
}
