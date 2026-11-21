package com.atria.reminder.output;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The last gate before anything is printed (constitution IV). Nothing reaches stdout without passing both passes
 * here: the shape fixed by evals/output.schema.json, and the invariants INV-02..INV-08.
 */
public final class OutputValidator {

    public static final String CANARY = "RMDR-CANARY-7731";
    public static final int MAX_TEXT = 200;
    public static final int MAX_MESSAGE = 300;
    public static final int MAX_HORIZON_DAYS = 366;
    public static final int MIN_SNOOZE = 1;
    public static final int MAX_SNOOZE = 1440;

    private static final Pattern DUE_AT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:00[+-]\\d{2}:\\d{2}$");

    private OutputValidator() {
    }

    /**
     * @param serialised the exact JSON that would be written, used for the canary scan
     * @return human-readable violations; an empty list means the decision may be printed
     */
    public static List<String> validate(Decision d, OffsetDateTime now, String serialised) {
        List<String> errors = new ArrayList<>();
        shape(d, errors);
        invariants(d, now, errors);
        canary(serialised, errors);
        return errors;
    }

    private static void shape(Decision d, List<String> e) {
        if (d == null) {
            e.add("INV-01 decision is null");
            return;
        }
        if (d.intent() == null) {
            e.add("INV-01 intent is required");
        }
        if (d.message() == null || d.message().isEmpty()) {
            e.add("INV-01 message must be 1..300 characters, got " + (d.message() == null ? "null" : "empty"));
        } else if (d.message().length() > MAX_MESSAGE) {
            e.add("INV-01 message longer than " + MAX_MESSAGE + " (len=" + d.message().length() + ")");
        }
        if (d.task() != null && d.task().length() > MAX_TEXT) {
            e.add("INV-01 task longer than " + MAX_TEXT + " (len=" + d.task().length() + ")");
        }
        if (d.target() != null && d.target().length() > MAX_TEXT) {
            e.add("INV-01 target longer than " + MAX_TEXT + " (len=" + d.target().length() + ")");
        }
        if (d.missingFields() == null) {
            e.add("INV-01 missing_fields must be an array, got null");
        } else {
            Set<MissingField> seen = new HashSet<>();
            for (MissingField f : d.missingFields()) {
                if (f == null) {
                    e.add("INV-01 missing_fields contains null");
                } else if (!seen.add(f)) {
                    e.add("INV-01 missing_fields items are not unique: " + f.wire());
                }
            }
        }
        if (d.dueAt() != null && !DUE_AT.matcher(d.dueAt()).matches()) {
            e.add("INV-01 due_at '" + d.dueAt() + "' does not match the required format");
        }
    }

    private static void invariants(Decision d, OffsetDateTime now, List<String> e) {
        if (d == null || d.intent() == null) {
            return;
        }
        boolean noGaps = d.missingFields() != null && d.missingFields().isEmpty();
        switch (d.intent()) {
            case CREATE -> {
                if (isBlank(d.task())) {
                    e.add("INV-02 create requires a non-empty task");
                }
                if (d.dueAt() == null) {
                    e.add("INV-02 create requires due_at");
                }
                if (d.target() != null || d.snoozeMinutes() != null) {
                    e.add("INV-02 create must have target=null and snooze_minutes=null");
                }
                if (!noGaps || d.reasonCode() != null) {
                    e.add("INV-02 create must have missing_fields=[] and reason_code=null");
                }
            }
            case RESCHEDULE -> {
                if (isBlank(d.target())) {
                    e.add("INV-03 reschedule requires a non-empty target");
                }
                if (d.dueAt() == null) {
                    e.add("INV-03 reschedule requires due_at");
                }
                if (d.snoozeMinutes() != null || !noGaps || d.reasonCode() != null) {
                    e.add("INV-03 reschedule must have snooze_minutes=null, missing_fields=[], reason_code=null");
                }
            }
            case SNOOZE -> {
                if (isBlank(d.target())) {
                    e.add("INV-04 snooze requires a non-empty target");
                }
                Integer m = d.snoozeMinutes();
                if (m == null || m < MIN_SNOOZE || m > MAX_SNOOZE) {
                    e.add("INV-04 snooze_minutes must be an integer in " + MIN_SNOOZE + ".." + MAX_SNOOZE
                            + ", got " + m);
                }
                if (d.dueAt() != null || !noGaps || d.reasonCode() != null) {
                    e.add("INV-04 snooze must have due_at=null, missing_fields=[], reason_code=null");
                }
            }
            case CLARIFY -> {
                if (d.missingFields() == null || d.missingFields().isEmpty()) {
                    e.add("INV-05 clarify requires non-empty missing_fields");
                }
                if (d.dueAt() != null || d.snoozeMinutes() != null) {
                    e.add("INV-05 clarify must not carry an action (due_at and snooze_minutes must be null)");
                }
                if (d.message() != null && !d.message().stripTrailing().endsWith("?")) {
                    e.add("INV-05 clarify message must be a question ending with '?'");
                }
                if (d.reasonCode() != null && !ReasonCode.CLARIFY_CODES.contains(d.reasonCode())) {
                    e.add("INV-05 clarify reason_code must be one of " + ReasonCode.CLARIFY_CODES + ", got "
                            + d.reasonCode());
                }
            }
            case REJECT -> {
                if (d.reasonCode() == null || !ReasonCode.REJECT_CODES.contains(d.reasonCode())) {
                    e.add("INV-06 reject requires reason_code in " + ReasonCode.REJECT_CODES + ", got "
                            + d.reasonCode());
                }
                if (d.task() != null || d.target() != null || d.dueAt() != null || d.snoozeMinutes() != null) {
                    e.add("INV-06 reject must have task, target, due_at, snooze_minutes all null");
                }
                if (!noGaps) {
                    e.add("INV-06 reject must have missing_fields=[]");
                }
            }
            default -> e.add("INV-01 unknown intent " + d.intent());
        }
        if (d.dueAt() != null) {
            dueAt(d.dueAt(), now, e);
        }
    }

    private static void dueAt(String raw, OffsetDateTime now, List<String> e) {
        OffsetDateTime due;
        try {
            due = OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ex) {
            e.add("INV-07 due_at " + raw + " is not a valid offset date-time");
            return;
        }
        if (!due.getOffset().equals(now.getOffset())) {
            e.add("INV-07 due_at offset " + due.getOffset() + " differs from --now offset " + now.getOffset());
        }
        if (!due.isAfter(now)) {
            e.add("INV-07 due_at " + raw + " is not after --now " + now);
        }
        if (due.isAfter(now.plusDays(MAX_HORIZON_DAYS))) {
            e.add("INV-07 due_at " + raw + " is more than " + MAX_HORIZON_DAYS + " days after --now");
        }
    }

    private static void canary(String serialised, List<String> e) {
        if (serialised != null && serialised.contains(CANARY)) {
            e.add("INV-08 output contains the system-prompt canary");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
