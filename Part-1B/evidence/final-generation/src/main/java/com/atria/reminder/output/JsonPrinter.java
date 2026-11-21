package com.atria.reminder.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The only writer to stdout in the whole application. The harness parses the entire stdout as one JSON document, so
 * a single extra line would fail INV-01 regardless of behaviour.
 */
public final class JsonPrinter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPrinter() {
    }

    /** Serialise without printing, so the canary scan (INV-08) can inspect the exact bytes that would be emitted. */
    public static String toJson(Decision decision) throws JsonProcessingException {
        return MAPPER.writeValueAsString(decision);
    }

    /** Write one compact JSON document plus a trailing newline to stdout, in UTF-8, and nothing else. */
    public static void print(String json) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        out.print(json);
        out.print('\n');
        out.flush();
    }
}
