package com.atria.reminder.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonPrinterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> KEYS = List.of(
            "intent", "task", "target", "due_at", "snooze_minutes", "missing_fields", "reason_code", "message");

    @Test
    void exactlyTheEightKeysAreSerialisedInOrder() throws Exception {
        String json = JsonPrinter.toJson(
                Decision.create("call the dentist", "2026-11-15T15:00:00+05:30", "Reminder set."));
        JsonNode node = MAPPER.readTree(json);

        List<String> actual = new ArrayList<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(KEYS, actual, "all eight keys, in schema order, with no others");
    }

    @Test
    void nullableFieldsAreSerialisedAsNullRatherThanOmitted() throws Exception {
        String json = JsonPrinter.toJson(Decision.reject(ReasonCode.EMPTY_INPUT, "No text received."));
        JsonNode node = MAPPER.readTree(json);
        for (String key : List.of("task", "target", "due_at", "snooze_minutes")) {
            assertTrue(node.has(key), key + " must be present");
            assertTrue(node.get(key).isNull(), key + " must be null, not omitted or empty");
        }
        assertTrue(node.get("missing_fields").isArray());
        assertEquals(0, node.get("missing_fields").size());
    }

    @Test
    void enumsSerialiseToTheirWireValues() throws Exception {
        JsonNode created = MAPPER.readTree(JsonPrinter.toJson(
                Decision.create("x", "2026-11-15T15:00:00+05:30", "ok")));
        assertEquals("create", created.get("intent").asText());

        JsonNode clarified = MAPPER.readTree(JsonPrinter.toJson(Decision.clarify(
                null, null, List.of(MissingField.TIME, MissingField.TASK), ReasonCode.PAST_TIME, "When?")));
        assertEquals("clarify", clarified.get("intent").asText());
        assertEquals("PAST_TIME", clarified.get("reason_code").asText());
        assertEquals("time", clarified.get("missing_fields").get(0).asText());
        assertEquals("task", clarified.get("missing_fields").get(1).asText());
    }

    @Test
    void theWholeDocumentIsOneCompactJsonObject() throws Exception {
        String json = JsonPrinter.toJson(Decision.snooze("the standup reminder", 20, "Snoozed."));
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        assertTrue(!json.contains("\n"), "compact form keeps one trial to one line");
        MAPPER.readTree(json); // parses as a single document or this throws
    }
}
