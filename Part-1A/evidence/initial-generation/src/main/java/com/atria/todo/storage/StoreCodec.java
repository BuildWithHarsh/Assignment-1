package com.atria.todo.storage;

import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.Priority;
import com.atria.todo.domain.Status;
import com.atria.todo.domain.Task;
import com.atria.todo.domain.TodoException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the single configured {@link ObjectMapper} and turns store text into a {@link TaskStore} and
 * back.
 *
 * <p>Two Jackson defaults are wrong for this spec and are corrected explicitly: {@code java.time}
 * values serialise as a decimal epoch rather than an ISO string, and unknown properties are accepted
 * silently (research R1, R10). Missing members deserialise to {@code null} or {@code 0} without
 * complaint, so the presence checks below — not the parser's defaults — are what enforce strictness.</p>
 *
 * <p>This class is the single owner of every content and shape fault, the zero-byte file included.
 * {@link JsonTaskRepository} decides only whether the file is absent or present. A rejected store is
 * never repaired, upgraded, migrated or overwritten (FR-029b, FR-031, FR-031a).</p>
 */
public final class StoreCodec {

    private static final ObjectMapper MAPPER = createMapper();
    private static final String BAD_STORE = "data file is not a valid task store";
    private static final String COUNTER = "lastIssuedId";
    private static final String TASKS = "tasks";
    private static final Set<String> ROOT_MEMBERS = Set.of(COUNTER, TASKS);
    private static final Set<String> TASK_MEMBERS = Set.copyOf(TaskJson.FIELDS);
    private static final Set<String> PRIORITY_NAMES = names(Priority.values());
    private static final Set<String> STATUS_NAMES = names(Status.values());

    private StoreCodec() {
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    private static Set<String> names(Enum<?>[] values) {
        List<String> collected = new ArrayList<>(values.length);
        for (Enum<?> value : values) {
            collected.add(value.name());
        }
        return Set.copyOf(collected);
    }

    /**
     * Renders a value with the one configured mapper, so the store file and stdout cannot drift. The
     * mapper itself is never handed out, which would let a caller reconfigure it.
     *
     * @param document the value to render
     * @return the JSON text, with no trailing newline
     * @throws TodoException {@code STORAGE_ERROR} when the value cannot be rendered
     */
    public static String toJson(Object document) {
        try {
            return MAPPER.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new TodoException(ErrorCode.STORAGE_ERROR, "could not render JSON");
        }
    }

    /**
     * Parses and strictly validates store text.
     *
     * @param content the file contents, decoded as UTF-8
     * @return the store
     * @throws TodoException {@code STORAGE_ERROR} for empty, unparseable or wrong-shaped content
     */
    public static TaskStore parse(String content) {
        if (content == null || content.isBlank()) {
            throw badStore();
        }
        JsonNode root = readTree(content);
        requireMembers(root, ROOT_MEMBERS);
        JsonNode counter = root.get(COUNTER);
        if (!counter.isIntegralNumber() || !counter.canConvertToInt() || counter.intValue() < 0) {
            throw badStore();
        }
        JsonNode tasks = root.get(TASKS);
        if (!tasks.isArray()) {
            throw badStore();
        }
        return new TaskStore(readTasks(tasks), counter.intValue());
    }

    /**
     * Serialises a store.
     *
     * @param store the store
     * @return the UTF-8 bytes to write
     * @throws TodoException {@code STORAGE_ERROR} when the store cannot be rendered
     */
    public static byte[] serialise(TaskStore store) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(COUNTER, store.lastIssuedId());
        root.put(TASKS, TaskJson.toList(store.tasks()));
        return toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static List<Task> readTasks(JsonNode tasks) {
        List<Task> parsed = new ArrayList<>(tasks.size());
        for (JsonNode node : tasks) {
            validateTask(node);
            parsed.add(TaskJson.fromNode(node));
        }
        return parsed;
    }

    private static void validateTask(JsonNode node) {
        requireMembers(node, TASK_MEMBERS);
        JsonNode id = node.get("id");
        if (!id.isIntegralNumber() || !id.canConvertToInt() || id.intValue() < 1) {
            throw badStore();
        }
        requireText(node.get("title"));
        requireConstant(node.get("priority"), PRIORITY_NAMES);
        requireConstant(node.get("status"), STATUS_NAMES);
        requireTags(node.get("tags"));
        requireInstant(node.get("createdAt"));
    }

    /**
     * Requires an object holding exactly the expected member names. Comparing the member count as well
     * as the names rejects a missing member and an unknown one in the same check.
     */
    private static void requireMembers(JsonNode node, Set<String> expected) {
        if (!node.isObject() || node.size() != expected.size()) {
            throw badStore();
        }
        Iterator<String> present = node.fieldNames();
        while (present.hasNext()) {
            if (!expected.contains(present.next())) {
                throw badStore();
            }
        }
    }

    private static void requireText(JsonNode node) {
        if (!node.isTextual()) {
            throw badStore();
        }
    }

    private static void requireConstant(JsonNode node, Set<String> allowed) {
        requireText(node);
        if (!allowed.contains(node.textValue())) {
            throw badStore();
        }
    }

    private static void requireTags(JsonNode node) {
        if (!node.isArray()) {
            throw badStore();
        }
        for (JsonNode tag : node) {
            requireText(tag);
        }
    }

    private static void requireInstant(JsonNode node) {
        requireText(node);
        String text = node.textValue();
        if (!text.endsWith("Z")) {
            throw badStore();
        }
        try {
            Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw badStore();
        }
    }

    private static JsonNode readTree(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw badStore();
        }
    }

    private static TodoException badStore() {
        return new TodoException(ErrorCode.STORAGE_ERROR, BAD_STORE);
    }
}
