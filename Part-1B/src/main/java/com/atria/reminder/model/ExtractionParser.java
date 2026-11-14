package com.atria.reminder.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;

/**
 * Strict reader for the model envelope. Required fields must be present, unknown fields are rejected, and text
 * fields are trimmed with blank becoming null. Values are not clamped or corrected.
 */
public final class ExtractionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> TOP_LEVEL = List.of(
            "action", "task", "target", "task_vague", "target_vague", "time", "snooze_minutes", "safety");

    private static final List<String> TIME_FIELDS = List.of(
            "kind", "relative_amount", "relative_unit", "date_kind", "weekday",
            "year", "month", "day", "hour", "minute", "meridiem");

    private ExtractionParser() {
    }

    public static Extraction parse(String json) throws ExtractionException {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json.trim());
        } catch (JsonProcessingException e) {
            throw new ExtractionException("model reply is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ExtractionException("model reply is not a JSON object");
        }
        require(root, TOP_LEVEL, "");
        rejectUnknown(root, TOP_LEVEL, "");

        JsonNode timeNode = root.get("time");
        if (!timeNode.isObject()) {
            throw new ExtractionException("field 'time' must be an object");
        }
        require(timeNode, TIME_FIELDS, "time.");
        rejectUnknown(timeNode, TIME_FIELDS, "time.");

        TimeExpression time = new TimeExpression(
                enumOf(TimeExpression.Kind.class, timeNode, "kind", "time."),
                intOf(timeNode, "relative_amount", "time."),
                enumOf(TimeExpression.Unit.class, timeNode, "relative_unit", "time."),
                enumOf(TimeExpression.DateKind.class, timeNode, "date_kind", "time."),
                enumOf(TimeExpression.Weekday.class, timeNode, "weekday", "time."),
                intOf(timeNode, "year", "time."),
                intOf(timeNode, "month", "time."),
                intOf(timeNode, "day", "time."),
                intOf(timeNode, "hour", "time."),
                intOf(timeNode, "minute", "time."),
                enumOf(TimeExpression.Meridiem.class, timeNode, "meridiem", "time."));

        if (time.kind() == null) {
            throw new ExtractionException("field 'time.kind' is required");
        }
        TimeExpression.DateKind dk = time.dateKind();
        if (dk == null) {
            throw new ExtractionException("field 'time.date_kind' is required");
        }

        Extraction.Action action = enumOf(Extraction.Action.class, root, "action", "");
        if (action == null) {
            throw new ExtractionException("field 'action' is required");
        }
        Extraction.Safety safety = enumOf(Extraction.Safety.class, root, "safety", "");
        if (safety == null) {
            throw new ExtractionException("field 'safety' is required");
        }

        return new Extraction(
                action,
                textOf(root, "task"),
                textOf(root, "target"),
                boolOf(root, "task_vague"),
                boolOf(root, "target_vague"),
                time,
                intOf(root, "snooze_minutes", ""),
                safety);
    }

    private static void require(JsonNode node, List<String> fields, String prefix) throws ExtractionException {
        for (String f : fields) {
            if (!node.has(f)) {
                throw new ExtractionException("field '" + prefix + f + "' is required but missing");
            }
        }
    }

    private static void rejectUnknown(JsonNode node, List<String> allowed, String prefix)
            throws ExtractionException {
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new ExtractionException("unexpected field '" + prefix + name + "'");
            }
        }
    }

    private static String textOf(JsonNode node, String field) throws ExtractionException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new ExtractionException("field '" + field + "' must be a string or null");
        }
        String trimmed = v.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer intOf(JsonNode node, String field, String prefix) throws ExtractionException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isIntegralNumber()) {
            throw new ExtractionException("field '" + prefix + field + "' must be an integer or null");
        }
        return v.asInt();
    }

    private static boolean boolOf(JsonNode node, String field) throws ExtractionException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return false;
        }
        if (!v.isBoolean()) {
            throw new ExtractionException("field '" + field + "' must be a boolean");
        }
        return v.asBoolean();
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, JsonNode node, String field, String prefix)
            throws ExtractionException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new ExtractionException("field '" + prefix + field + "' must be a string or null");
        }
        String raw = v.asText().trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new ExtractionException("field '" + prefix + field + "' has unsupported value '" + v.asText() + "'");
        }
    }
}
