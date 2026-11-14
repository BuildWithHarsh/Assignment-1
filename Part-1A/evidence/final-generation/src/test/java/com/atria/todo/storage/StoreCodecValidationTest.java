package com.atria.todo.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atria.todo.cli.CliFixture;
import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.TodoException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One case per row of the validation table in {@code contracts/store-schema.md}. Every row is a
 * {@code STORAGE_ERROR} with the file left byte-for-byte unchanged, never repaired or overwritten
 * (FR-029b, FR-031).
 */
class StoreCodecValidationTest {

    private static final String TASK = "{\"id\":1,\"title\":\"Buy milk\",\"priority\":\"MEDIUM\","
            + "\"status\":\"TODO\",\"tags\":[],\"createdAt\":\"2026-11-14T09:15:30Z\"}";

    @TempDir
    private Path directory;

    private static String store(String task) {
        return "{\"lastIssuedId\":1,\"tasks\":[" + task + "]}";
    }

    /** Rejects the content through the codec, and through both a read and a write command. */
    private void rejected(String label, String content) throws IOException {
        TodoException thrown = assertThrows(TodoException.class, () -> StoreCodec.parse(content), label);
        assertEquals(ErrorCode.STORAGE_ERROR, thrown.code(), label);

        Path path = directory.resolve("store-" + Integer.toHexString(label.hashCode()) + ".json");
        byte[] before = content.getBytes(StandardCharsets.UTF_8);
        Files.write(path, before);
        CliFixture cli = new CliFixture(path);
        cli.run("list").failure(5, "STORAGE_ERROR");
        cli.run("add", "--title", "x").failure(5, "STORAGE_ERROR");
        assertArrayEquals(before, Files.readAllBytes(path), label + ": file must be unchanged");
    }

    @Test
    @DisplayName("content that is not valid JSON is rejected")
    void notValidJson() throws IOException {
        rejected("not json", "{ this is not json");
        rejected("trailing comma", "{\"lastIssuedId\":1,\"tasks\":[],}");
        rejected("comment", "// nope\n{\"lastIssuedId\":1,\"tasks\":[]}");
    }

    @Test
    @DisplayName("a bare array is rejected, including an array of tasks")
    void bareArray() throws IOException {
        rejected("empty array", "[]");
        rejected("array of tasks", "[" + TASK + "]");
    }

    @Test
    @DisplayName("a JSON scalar is rejected")
    void scalars() throws IOException {
        rejected("number", "42");
        rejected("string", "\"x\"");
        rejected("boolean", "true");
        rejected("null", "null");
    }

    @Test
    @DisplayName("a missing member is rejected rather than defaulted")
    void missingRootMembers() throws IOException {
        rejected("no tasks", "{\"lastIssuedId\":1}");
        rejected("no lastIssuedId", "{\"tasks\":[]}");
        rejected("neither", "{}");
    }

    @Test
    @DisplayName("lastIssuedId must be a non-negative integer")
    void badCounter() throws IOException {
        rejected("counter as string", "{\"lastIssuedId\":\"1\",\"tasks\":[]}");
        rejected("counter as decimal", "{\"lastIssuedId\":1.5,\"tasks\":[]}");
        rejected("counter negative", "{\"lastIssuedId\":-1,\"tasks\":[]}");
        rejected("counter null", "{\"lastIssuedId\":null,\"tasks\":[]}");
    }

    @Test
    @DisplayName("tasks must be an array")
    void badTasksMember() throws IOException {
        rejected("tasks as object", "{\"lastIssuedId\":1,\"tasks\":{}}");
        rejected("tasks as string", "{\"lastIssuedId\":1,\"tasks\":\"none\"}");
        rejected("tasks null", "{\"lastIssuedId\":1,\"tasks\":null}");
        rejected("task not an object", "{\"lastIssuedId\":1,\"tasks\":[7]}");
    }

    @Test
    @DisplayName("an unknown member at the root or inside a task is rejected")
    void unknownMembers() throws IOException {
        rejected("unknown at root", "{\"lastIssuedId\":1,\"tasks\":[],\"extra\":1}");
        rejected("unknown in task", store(TASK.replace("{\"id\":1", "{\"extra\":9,\"id\":1")));
    }

    @Test
    @DisplayName("a task missing any of the six fields is rejected")
    void missingTaskFields() throws IOException {
        rejected("no id", store("{\"title\":\"a\",\"priority\":\"LOW\",\"status\":\"TODO\","
                + "\"tags\":[],\"createdAt\":\"2026-11-14T09:15:30Z\"}"));
        rejected("no title", store("{\"id\":1,\"priority\":\"LOW\",\"status\":\"TODO\","
                + "\"tags\":[],\"createdAt\":\"2026-11-14T09:15:30Z\"}"));
        rejected("no priority", store("{\"id\":1,\"title\":\"a\",\"status\":\"TODO\","
                + "\"tags\":[],\"createdAt\":\"2026-11-14T09:15:30Z\"}"));
        rejected("no status", store("{\"id\":1,\"title\":\"a\",\"priority\":\"LOW\","
                + "\"tags\":[],\"createdAt\":\"2026-11-14T09:15:30Z\"}"));
        rejected("no tags", store("{\"id\":1,\"title\":\"a\",\"priority\":\"LOW\",\"status\":\"TODO\","
                + "\"createdAt\":\"2026-11-14T09:15:30Z\"}"));
        rejected("no createdAt", store("{\"id\":1,\"title\":\"a\",\"priority\":\"LOW\","
                + "\"status\":\"TODO\",\"tags\":[]}"));
    }

    @Test
    @DisplayName("a task field of the wrong type is rejected")
    void wrongFieldTypes() throws IOException {
        rejected("id as string", store(TASK.replace("\"id\":1", "\"id\":\"1\"")));
        rejected("id as decimal", store(TASK.replace("\"id\":1", "\"id\":1.5")));
        rejected("title as number", store(TASK.replace("\"title\":\"Buy milk\"", "\"title\":7")));
        rejected("tags as string", store(TASK.replace("\"tags\":[]", "\"tags\":\"home\"")));
        rejected("tag not a string", store(TASK.replace("\"tags\":[]", "\"tags\":[7]")));
        rejected("createdAt as number", store(TASK.replace("\"createdAt\":\"2026-11-14T09:15:30Z\"",
                "\"createdAt\":1763100000")));
        rejected("createdAt unparseable", store(TASK.replace("2026-11-14T09:15:30Z", "yesterday")));
    }

    @Test
    @DisplayName("priority and status must name known constants, spelled exactly")
    void unknownConstants() throws IOException {
        rejected("unknown priority", store(TASK.replace("\"MEDIUM\"", "\"URGENT\"")));
        rejected("lower-case priority", store(TASK.replace("\"MEDIUM\"", "\"medium\"")));
        rejected("unknown status", store(TASK.replace("\"TODO\"", "\"BLOCKED\"")));
        rejected("lower-case status", store(TASK.replace("\"TODO\"", "\"todo\"")));
    }

    @Test
    @DisplayName("a well-formed store is accepted")
    void wellFormedStoreAccepted() {
        TaskStore empty = StoreCodec.parse("{\"lastIssuedId\":0,\"tasks\":[]}");
        assertEquals(0, empty.lastIssuedId());
        assertEquals(0, empty.tasks().size());

        TaskStore one = StoreCodec.parse(store(TASK));
        assertEquals(1, one.lastIssuedId());
        assertEquals(1, one.tasks().size());
        assertEquals("Buy milk", one.tasks().get(0).title());

        TaskStore emptied = StoreCodec.parse("{\"lastIssuedId\":9,\"tasks\":[]}");
        assertEquals(9, emptied.lastIssuedId(), "the counter must survive an empty task list");
    }

    @Test
    @DisplayName("the member order inside the file does not matter")
    void memberOrderIsNotSignificant() {
        TaskStore store = StoreCodec.parse("{\"tasks\":[],\"lastIssuedId\":2}");
        assertEquals(2, store.lastIssuedId());
    }
}
