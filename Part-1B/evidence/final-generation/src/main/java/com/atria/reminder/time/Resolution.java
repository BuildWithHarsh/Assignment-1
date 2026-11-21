package com.atria.reminder.time;

import java.time.OffsetDateTime;

/**
 * The closed outcome set of TimeResolver, so DecisionMapper cannot forget a case.
 */
public sealed interface Resolution {

    /** A valid instant, strictly after --now and within 366 days (INV-07). */
    record Resolved(OffsetDateTime at) implements Resolution {
    }

    /** Valid but not after --now: clarify PAST_TIME (FR-05). */
    record Past(OffsetDateTime at) implements Resolution {
    }

    /** Impossible date or time: clarify INVALID_TIME (FR-06). */
    record Invalid(String part) implements Resolution {
    }

    /** A time was referred to but not pinned down: clarify, no reason code (FR-07). */
    record Vague() implements Resolution {
    }

    /** No time expressed at all: clarify, no reason code (FR-07). */
    record Absent() implements Resolution {
    }

    /** Beyond --now + 366 days: clarify INVALID_TIME (FR-06, INV-07). */
    record OutOfHorizon(OffsetDateTime at) implements Resolution {
    }
}
