package com.atria.reminder.output;

import com.fasterxml.jackson.annotation.JsonValue;

/** The five decision kinds allowed by the output schema. */
public enum Intent {
    CREATE("create"),
    RESCHEDULE("reschedule"),
    SNOOZE("snooze"),
    CLARIFY("clarify"),
    REJECT("reject");

    private final String wire;

    Intent(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
