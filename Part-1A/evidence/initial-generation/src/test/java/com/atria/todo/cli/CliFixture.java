package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Drives {@link CliRunner} the way the real process does — one invocation, two captured streams, one
 * returned exit code — so the stdout/stderr/exit contract is asserted on both sides in unit tests.
 */
public final class CliFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path data;
    private int exitCode;
    private String stdout = "";
    private String stderr = "";

    /**
     * Creates a fixture against one data file.
     *
     * @param data the data file path, which need not exist
     */
    public CliFixture(Path data) {
        this.data = data;
    }

    /**
     * Runs a command with {@code --data} prepended.
     *
     * @param args the command name and its options
     * @return this fixture
     */
    public CliFixture run(String... args) {
        String[] full = new String[args.length + 2];
        full[0] = ArgParser.DATA;
        full[1] = data.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return runRaw(full);
    }

    /**
     * Runs a command exactly as given, with nothing prepended.
     *
     * @param args the raw arguments
     * @return this fixture
     */
    public CliFixture runRaw(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        exitCode = new CliRunner(out, err).run(args);
        stdout = out.toString(StandardCharsets.UTF_8);
        stderr = err.toString(StandardCharsets.UTF_8);
        return this;
    }

    /**
     * The exit code of the last run.
     *
     * @return the exit code
     */
    public int exitCode() {
        return exitCode;
    }

    /**
     * The raw stdout of the last run.
     *
     * @return the captured bytes, decoded as UTF-8
     */
    public String stdout() {
        return stdout;
    }

    /**
     * The raw stderr of the last run.
     *
     * @return the captured bytes, decoded as UTF-8
     */
    public String stderr() {
        return stderr;
    }

    /**
     * Asserts the success contract and returns the parsed document.
     *
     * @return the single JSON document written to stdout
     */
    public JsonNode success() {
        assertEquals(0, exitCode, "expected success, stderr was: " + stderr);
        assertEquals("", stderr, "nothing may reach stderr on success");
        return parse(stdout, "stdout");
    }

    /**
     * Asserts the failure contract: the exit code, an empty stdout, and exactly one error document on
     * stderr whose code matches.
     *
     * @param expectedExit the expected exit code
     * @param expectedCode the expected {@code error.code}
     * @return the {@code error} object
     */
    public JsonNode failure(int expectedExit, String expectedCode) {
        assertEquals(expectedExit, exitCode, "unexpected exit code; stderr was: " + stderr);
        assertEquals("", stdout, "nothing may reach stdout on failure");
        JsonNode document = parse(stderr, "stderr");
        JsonNode error = document.get("error");
        assertNotNull(error, "stderr must hold an error object: " + stderr);
        assertEquals(expectedCode, error.get("code").textValue());
        assertTrue(error.get("message").textValue() != null && !error.get("message").textValue().isEmpty(),
                "an error document must carry a message");
        return error;
    }

    /**
     * The message text of the last failure.
     *
     * @return the message
     */
    public String errorMessage() {
        return parse(stderr, "stderr").get("error").get("message").textValue();
    }

    private static JsonNode parse(String text, String streamName) {
        assertEquals(1, text.chars().filter(c -> c == '\n').count(),
                "exactly one document, newline-terminated, must reach " + streamName + ": " + text);
        try {
            return MAPPER.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return fail(streamName + " was not one JSON document: " + text);
        }
    }
}
