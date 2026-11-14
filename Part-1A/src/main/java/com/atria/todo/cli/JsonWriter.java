package com.atria.todo.cli;

import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.TodoException;
import com.atria.todo.storage.StoreCodec;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes exactly one JSON document to an injected stream, in UTF-8 and with an explicit {@code \n}
 * terminator so the bytes do not vary by platform.
 *
 * <p>Rendering goes through {@link StoreCodec}, so stdout and the store file are produced by the same
 * mapper configuration.</p>
 */
public final class JsonWriter {

    private final PrintStream stream;

    /**
     * Wraps a stream.
     *
     * @param target where the document goes; always injected rather than taken from the JVM's standard
     *               streams, so the stream contract is assertable in unit tests
     */
    public JsonWriter(OutputStream target) {
        this.stream = new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    /**
     * Writes one success document.
     *
     * @param document the value to render: a task map, a list of them, or {@code {"deleted": id}}
     * @throws TodoException {@code STORAGE_ERROR} when the value cannot be rendered
     */
    public void write(Object document) {
        emit(StoreCodec.toJson(document));
    }

    /**
     * Writes the one error document, {@code {"error":{"code":"<CODE>","message":"<text>"}}}.
     *
     * @param code    the failure class
     * @param message fixed wording, optionally suffixed with the user's offending value
     */
    public void writeError(ErrorCode code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", message);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("error", error);
        String rendered;
        try {
            rendered = StoreCodec.toJson(document);
        } catch (TodoException e) {
            rendered = "{\"error\":{\"code\":\"" + code.name() + "\",\"message\":\"unrenderable\"}}";
        }
        emit(rendered);
    }

    private void emit(String json) {
        stream.print(json);
        stream.print('\n');
        stream.flush();
    }
}
