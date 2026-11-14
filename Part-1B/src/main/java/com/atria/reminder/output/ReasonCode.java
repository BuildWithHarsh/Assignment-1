package com.atria.reminder.output;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.EnumSet;
import java.util.Set;

/**
 * The seven reason codes allowed by the output schema. The absence of a reason is expressed as a null
 * {@code reason_code}, never as a sentinel member of this enum.
 */
public enum ReasonCode {
    EMPTY_INPUT,
    INPUT_TOO_LONG,
    UNSUPPORTED_REQUEST,
    UNSAFE_REQUEST,
    PAST_TIME,
    INVALID_TIME,
    SNOOZE_OUT_OF_RANGE;

    /** The four codes a {@code reject} may carry (INV-06). */
    public static final Set<ReasonCode> REJECT_CODES =
            EnumSet.of(EMPTY_INPUT, INPUT_TOO_LONG, UNSUPPORTED_REQUEST, UNSAFE_REQUEST);

    /** The three codes a {@code clarify} may carry — a time was supplied and found unusable. */
    public static final Set<ReasonCode> CLARIFY_CODES =
            EnumSet.of(PAST_TIME, INVALID_TIME, SNOOZE_OUT_OF_RANGE);

    @JsonValue
    public String wire() {
        return name();
    }
}
