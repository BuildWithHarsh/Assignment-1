package com.atria.reminder.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExtractionParserTest {

    private static String envelope(String overrides) {
        String base = """
                {"action":"create","task":"call the dentist","target":null,"task_vague":false,
                 "target_vague":false,
                 "time":{"kind":"absolute","relative_amount":null,"relative_unit":null,"date_kind":"tomorrow",
                         "weekday":null,"year":null,"month":null,"day":null,"hour":3,"minute":null,
                         "meridiem":"pm"},
                 "snooze_minutes":null,"safety":"ok"}
                """;
        return overrides == null ? base : overrides;
    }

    @Test
    void parsesAWellFormedEnvelope() throws Exception {
        Extraction e = ExtractionParser.parse(envelope(null));
        assertEquals(Extraction.Action.CREATE, e.action());
        assertEquals("call the dentist", e.task());
        assertNull(e.target());
        assertEquals(TimeExpression.Kind.ABSOLUTE, e.time().kind());
        assertEquals(TimeExpression.DateKind.TOMORROW, e.time().dateKind());
        assertEquals(3, e.time().hour());
        assertEquals(TimeExpression.Meridiem.PM, e.time().meridiem());
        assertEquals(Extraction.Safety.OK, e.safety());
        assertTrue(e.hasUsableTask());
    }

    @Test
    void aMissingRequiredFieldIsAnEnvelopeError() {
        String missingSafety = """
                {"action":"create","task":"x","target":null,"task_vague":false,"target_vague":false,
                 "time":{"kind":"none","relative_amount":null,"relative_unit":null,"date_kind":"none",
                         "weekday":null,"year":null,"month":null,"day":null,"hour":null,"minute":null,
                         "meridiem":null},
                 "snooze_minutes":null}
                """;
        ExtractionException ex = assertThrows(ExtractionException.class, () -> ExtractionParser.parse(missingSafety));
        assertTrue(ex.getMessage().contains("safety"), ex.getMessage());
    }

    @Test
    void aMissingNestedTimeFieldIsAnEnvelopeError() {
        String missingDateKind = """
                {"action":"create","task":"x","target":null,"task_vague":false,"target_vague":false,
                 "time":{"kind":"none","relative_amount":null,"relative_unit":null,"weekday":null,"year":null,
                         "month":null,"day":null,"hour":null,"minute":null,"meridiem":null},
                 "snooze_minutes":null,"safety":"ok"}
                """;
        ExtractionException ex = assertThrows(ExtractionException.class,
                () -> ExtractionParser.parse(missingDateKind));
        assertTrue(ex.getMessage().contains("time.date_kind"), ex.getMessage());
    }

    @Test
    void anUnknownFieldIsAnEnvelopeError() {
        String extra = """
                {"action":"create","task":"x","target":null,"task_vague":false,"target_vague":false,
                 "time":{"kind":"none","relative_amount":null,"relative_unit":null,"date_kind":"none",
                         "weekday":null,"year":null,"month":null,"day":null,"hour":null,"minute":null,
                         "meridiem":null},
                 "snooze_minutes":null,"safety":"ok","due_at":"2026-11-15T09:00:00+05:30"}
                """;
        ExtractionException ex = assertThrows(ExtractionException.class, () -> ExtractionParser.parse(extra));
        assertTrue(ex.getMessage().contains("due_at"), ex.getMessage());
    }

    @Test
    void nonJsonAndNonObjectRepliesAreEnvelopeErrors() {
        assertThrows(ExtractionException.class, () -> ExtractionParser.parse("here you go: {}"));
        assertThrows(ExtractionException.class, () -> ExtractionParser.parse("[]"));
        assertThrows(ExtractionException.class, () -> ExtractionParser.parse(""));
        assertThrows(ExtractionException.class, () -> ExtractionParser.parse(null));
    }

    @Test
    void aWrongTypeIsAnEnvelopeError() {
        String wrongType = """
                {"action":"create","task":"x","target":null,"task_vague":"yes","target_vague":false,
                 "time":{"kind":"none","relative_amount":null,"relative_unit":null,"date_kind":"none",
                         "weekday":null,"year":null,"month":null,"day":null,"hour":null,"minute":null,
                         "meridiem":null},
                 "snooze_minutes":null,"safety":"ok"}
                """;
        assertThrows(ExtractionException.class, () -> ExtractionParser.parse(wrongType));
    }

    @Test
    void anUnsupportedEnumValueIsAnEnvelopeError() {
        String badAction = """
                {"action":"cancel","task":"x","target":null,"task_vague":false,"target_vague":false,
                 "time":{"kind":"none","relative_amount":null,"relative_unit":null,"date_kind":"none",
                         "weekday":null,"year":null,"month":null,"day":null,"hour":null,"minute":null,
                         "meridiem":null},
                 "snooze_minutes":null,"safety":"ok"}
                """;
        assertThrows(ExtractionException.class, () -> ExtractionParser.parse(badAction));
    }

    @Test
    void outOfRangeValuesParseSuccessfullyBecauseTheyAreFrSixAndFrTenSignals() throws Exception {
        String impossible = """
                {"action":"create","task":"the dentist","target":null,"task_vague":false,"target_vague":false,
                 "time":{"kind":"absolute","relative_amount":null,"relative_unit":null,"date_kind":"explicit",
                         "weekday":null,"year":null,"month":11,"day":31,"hour":25,"minute":null,
                         "meridiem":null},
                 "snooze_minutes":4320,"safety":"ok"}
                """;
        Extraction e = ExtractionParser.parse(impossible);
        assertEquals(11, e.time().month());
        assertEquals(31, e.time().day(), "31 November must survive parsing so FR-06 can reject it");
        assertEquals(25, e.time().hour(), "hour 25 must survive parsing so FR-06 can reject it");
        assertEquals(4320, e.snoozeMinutes(), "an out-of-range snooze must survive so FR-10 can clarify it");
    }

    @Test
    void blankTextBecomesNullAndWhitespaceIsTrimmed() throws Exception {
        String blanks = """
                {"action":"create","task":"  call mom  ","target":"   ","task_vague":false,"target_vague":false,
                 "time":{"kind":"none","relative_amount":null,"relative_unit":null,"date_kind":"none",
                         "weekday":null,"year":null,"month":null,"day":null,"hour":null,"minute":null,
                         "meridiem":null},
                 "snooze_minutes":null,"safety":"ok"}
                """;
        Extraction e = ExtractionParser.parse(blanks);
        assertEquals("call mom", e.task());
        assertNull(e.target());
    }
}
