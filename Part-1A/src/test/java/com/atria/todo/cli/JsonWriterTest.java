package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.TodoException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Output rendering: one document, UTF-8, newline-terminated, nothing platform-specific. */
class JsonWriterTest {

    private final ByteArrayOutputStream target = new ByteArrayOutputStream();

    private String written() {
        return target.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a document is written once and terminated with a single newline")
    void writesOneDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("deleted", 3);
        new JsonWriter(target).write(document);
        assertEquals("{\"deleted\":3}\n", written());
    }

    @Test
    @DisplayName("the line terminator is a bare newline, not the platform separator")
    void terminatorIsDeterministic() {
        new JsonWriter(target).write(List.of());
        assertEquals("[]\n", written());
        assertEquals(1, written().chars().filter(c -> c == '\n').count());
        assertEquals(0, written().chars().filter(c -> c == '\r').count());
    }

    @Test
    @DisplayName("non-ASCII text is written as UTF-8")
    void writesUtf8() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("title", "café über");
        new JsonWriter(target).write(document);
        assertEquals("{\"title\":\"café über\"}\n", written());
    }

    @Test
    @DisplayName("the error document has the documented shape and escapes the message")
    void writesErrorDocument() {
        new JsonWriter(target).writeError(ErrorCode.VALIDATION_ERROR, "unknown priority: \"x\"");
        assertEquals("{\"error\":{\"code\":\"VALIDATION_ERROR\","
                + "\"message\":\"unknown priority: \\\"x\\\"\"}}\n", written());
    }

    @Test
    @DisplayName("every error code renders its own name")
    void everyCodeRenders() {
        for (ErrorCode code : ErrorCode.values()) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            new JsonWriter(stream).writeError(code, "message");
            assertEquals("{\"error\":{\"code\":\"" + code.name() + "\",\"message\":\"message\"}}\n",
                    stream.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("a value that cannot be rendered is a STORAGE_ERROR, not a stack trace")
    void unrenderableValueIsStorageError() {
        JsonWriter writer = new JsonWriter(target);
        TodoException thrown = assertThrows(TodoException.class, () -> writer.write(new Object()));
        assertEquals(ErrorCode.STORAGE_ERROR, thrown.code());
        assertEquals("", written(), "nothing may be written when rendering fails");
    }
}
