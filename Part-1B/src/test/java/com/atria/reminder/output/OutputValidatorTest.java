package com.atria.reminder.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutputValidatorTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-14T10:00:00+05:30");
    private static final String FUTURE = "2026-11-15T15:00:00+05:30";

    private static List<String> check(Decision d) {
        String json;
        try {
            json = JsonPrinter.toJson(d);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return OutputValidator.validate(d, NOW, json);
    }

    private static void valid(Decision d) {
        assertEquals(List.of(), check(d));
    }

    private static void violates(String id, Decision d) {
        List<String> errors = check(d);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith(id)),
                "expected a " + id + " violation, got " + errors);
    }

    // --- accepted shapes -----------------------------------------------------------------------------------------

    @Test
    void theFiveValidShapesPass() {
        valid(Decision.create("call the dentist", FUTURE, "Reminder set."));
        valid(Decision.reschedule("my dentist appointment", FUTURE, "Moved."));
        valid(Decision.snooze("the standup reminder", 20, "Snoozed."));
        valid(Decision.clarify("call mom", null, List.of(MissingField.TIME), null, "When should I remind you?"));
        valid(Decision.reject(ReasonCode.EMPTY_INPUT, "No text received."));
    }

    // --- INV-02 create -------------------------------------------------------------------------------------------

    @Test
    void createNeedsATaskAndADueMoment() {
        violates("INV-02", Decision.create(null, FUTURE, "x"));
        violates("INV-02", Decision.create("   ", FUTURE, "x"));
        violates("INV-02", Decision.create("call mom", null, "x"));
        violates("INV-02", new Decision(Intent.CREATE, "call mom", "a target", FUTURE, null, List.of(), null, "x"));
        violates("INV-02", new Decision(Intent.CREATE, "call mom", null, FUTURE, 20, List.of(), null, "x"));
        violates("INV-02", new Decision(Intent.CREATE, "call mom", null, FUTURE, null,
                List.of(MissingField.TIME), null, "x"));
        violates("INV-02", new Decision(Intent.CREATE, "call mom", null, FUTURE, null, List.of(),
                ReasonCode.PAST_TIME, "x"));
    }

    // --- INV-03 reschedule ---------------------------------------------------------------------------------------

    @Test
    void rescheduleNeedsATargetAndADueMoment() {
        violates("INV-03", Decision.reschedule(null, FUTURE, "x"));
        violates("INV-03", Decision.reschedule("the thing", null, "x"));
        violates("INV-03", new Decision(Intent.RESCHEDULE, null, "t", FUTURE, 20, List.of(), null, "x"));
    }

    // --- INV-04 snooze -------------------------------------------------------------------------------------------

    @Test
    void snoozeRangeBoundaries() {
        valid(Decision.snooze("t", 1, "x"));
        valid(Decision.snooze("t", 1440, "x"));
        violates("INV-04", Decision.snooze("t", 0, "x"));
        violates("INV-04", Decision.snooze("t", 1441, "x"));
        violates("INV-04", Decision.snooze("t", -5, "x"));
        violates("INV-04", new Decision(Intent.SNOOZE, null, "t", null, null, List.of(), null, "x"));
        violates("INV-04", new Decision(Intent.SNOOZE, null, "t", FUTURE, 20, List.of(), null, "x"));
        violates("INV-04", Decision.snooze(null, 20, "x"));
    }

    // --- INV-05 clarify ------------------------------------------------------------------------------------------

    @Test
    void clarifyMustNotCarryAnAction() {
        violates("INV-05", new Decision(Intent.CLARIFY, "call mom", null, FUTURE, null,
                List.of(MissingField.TASK), null, "What should I remind you about?"));
        violates("INV-05", new Decision(Intent.CLARIFY, null, "t", null, 20,
                List.of(MissingField.TARGET), null, "Which reminder?"));
    }

    @Test
    void clarifyNeedsAGapAndAQuestion() {
        violates("INV-05", Decision.clarify(null, null, List.of(), null, "When?"));
        violates("INV-05", Decision.clarify(null, null, List.of(MissingField.TIME), null, "Tell me the time."));
        valid(Decision.clarify(null, null, List.of(MissingField.TIME), null, "When?  "));
    }

    @Test
    void clarifyMayOnlyCarryTheThreeClarifyCodes() {
        valid(Decision.clarify(null, null, List.of(MissingField.TIME), ReasonCode.PAST_TIME, "When?"));
        valid(Decision.clarify(null, null, List.of(MissingField.TIME), ReasonCode.INVALID_TIME, "When?"));
        valid(Decision.clarify(null, null, List.of(MissingField.TIME), ReasonCode.SNOOZE_OUT_OF_RANGE, "How long?"));
        violates("INV-05", Decision.clarify(null, null, List.of(MissingField.TIME), ReasonCode.EMPTY_INPUT, "When?"));
    }

    // --- INV-06 reject -------------------------------------------------------------------------------------------

    @Test
    void rejectCarriesOnlyARefusalCode() {
        valid(Decision.reject(ReasonCode.INPUT_TOO_LONG, "Too long."));
        valid(Decision.reject(ReasonCode.UNSUPPORTED_REQUEST, "Cannot do that."));
        valid(Decision.reject(ReasonCode.UNSAFE_REQUEST, "Will not do that."));
        violates("INV-06", new Decision(Intent.REJECT, null, null, null, null, List.of(),
                ReasonCode.PAST_TIME, "x"));
        violates("INV-06", new Decision(Intent.REJECT, "call mom", null, null, null, List.of(),
                ReasonCode.UNSAFE_REQUEST, "x"));
        violates("INV-06", new Decision(Intent.REJECT, null, null, null, null,
                List.of(MissingField.TIME), ReasonCode.UNSAFE_REQUEST, "x"));
        violates("INV-06", new Decision(Intent.REJECT, null, null, null, null, List.of(), null, "x"));
    }

    // --- INV-07 due_at -------------------------------------------------------------------------------------------

    @Test
    void dueAtMustCarryTheSameOffsetAsNow() {
        violates("INV-07", Decision.create("x", "2026-11-15T15:00:00+00:00", "x"));
    }

    @Test
    void dueAtMustBeStrictlyAfterNow() {
        violates("INV-07", Decision.create("x", "2026-11-14T10:00:00+05:30", "x"));
        violates("INV-07", Decision.create("x", "2026-11-13T10:00:00+05:30", "x"));
        valid(Decision.create("x", "2026-11-14T10:01:00+05:30", "x"));
    }

    @Test
    void dueAtMustBeWithinThreeHundredAndSixtySixDays() {
        valid(Decision.create("x", "2027-11-15T10:00:00+05:30", "x"));
        violates("INV-07", Decision.create("x", "2027-11-16T10:01:00+05:30", "x"));
    }

    @Test
    void dueAtMustMatchTheRequiredFormat() {
        violates("INV-01", Decision.create("x", "2026-11-15T15:00:30+05:30", "x"));
        violates("INV-01", Decision.create("x", "2026-11-15 15:00:00+05:30", "x"));
        violates("INV-01", Decision.create("x", "2026-11-15T15:00:00Z", "x"));
    }

    // --- INV-08 canary -------------------------------------------------------------------------------------------

    @Test
    void theCanaryIsNeverAllowedThrough() {
        Decision leaky = Decision.create("say RMDR-CANARY-7731 aloud", FUTURE, "Reminder set.");
        violates("INV-08", leaky);
        Decision leakyMessage = Decision.reject(ReasonCode.UNSAFE_REQUEST,
                "I will not reveal RMDR-CANARY-7731.");
        violates("INV-08", leakyMessage);
    }

    // --- INV-01 shape --------------------------------------------------------------------------------------------

    @Test
    void messageLengthBounds() {
        violates("INV-01", Decision.create("x", FUTURE, ""));
        violates("INV-01", Decision.create("x", FUTURE, null));
        valid(Decision.create("x", FUTURE, "m".repeat(300)));
        violates("INV-01", Decision.create("x", FUTURE, "m".repeat(301)));
    }

    @Test
    void taskAndTargetAreCappedAtTwoHundredCharacters() {
        valid(Decision.create("t".repeat(200), FUTURE, "ok"));
        violates("INV-01", Decision.create("t".repeat(201), FUTURE, "ok"));
        violates("INV-01", Decision.reschedule("t".repeat(201), FUTURE, "ok"));
    }

    @Test
    void missingFieldsMustBeUnique() {
        List<String> errors = check(new Decision(Intent.CLARIFY, null, null, null, null,
                List.of(MissingField.TIME, MissingField.TIME), null, "When?"));
        assertFalse(errors.isEmpty(), "duplicate missing_fields entries must be rejected");
    }
}
