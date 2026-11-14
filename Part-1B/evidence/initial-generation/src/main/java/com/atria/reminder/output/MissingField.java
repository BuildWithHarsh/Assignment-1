package com.atria.reminder.output;

import com.fasterxml.jackson.annotation.JsonValue;

/** The only values {@code missing_fields} may contain. */
public enum MissingField {
    TASK("task"),
    TIME("time"),
    TARGET("target");

    private final String wire;

    MissingField(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
