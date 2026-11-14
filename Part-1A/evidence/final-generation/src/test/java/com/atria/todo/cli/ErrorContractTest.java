package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two-sided stream contract (FR-002, FR-003, AC-16): on failure stdout is empty and stderr holds
 * exactly one error document whose code matches the exit code; on success stderr is empty.
 *
 * <p>Both directions matter — a one-sided assertion passes for an implementation that chatters on the
 * other stream.</p>
 */
class ErrorContractTest {

    @TempDir
    private Path directory;

    private Path data;
    private CliFixture cli;

    @BeforeEach
    void setUp() {
        data = directory.resolve("tasks.json");
        cli = new CliFixture(data);
    }

    @Test
    @DisplayName("every success writes one document to stdout and nothing to stderr")
    void successIsSilentOnStderr() {
        cli.run("add", "--title", "a", "--tags", "x").success();
        cli.run("add", "--title", "b", "--priority", "HIGH").success();
        for (String[] command : List.of(new String[] {"list"}, new String[] {"get", "1"},
                new String[] {"list", "--tag", "x"}, new String[] {"move", "1", "IN_PROGRESS"},
                new String[] {"update", "1", "--priority", "LOW"}, new String[] {"delete", "2"})) {
            cli.run(command).success();
            assertEquals("", cli.stderr(), "nothing may reach stderr on success: " + command[0]);
            assertTrue(cli.stdout().endsWith("\n"), "the document must be newline-terminated");
        }
    }

    @Test
    @DisplayName("exit 2 carries VALIDATION_ERROR with an empty stdout")
    void validationErrors() {
        cli.runRaw("list").failure(2, "VALIDATION_ERROR");
        cli.run("frobnicate").failure(2, "VALIDATION_ERROR");
        cli.run("add", "--title", "x", "--colour", "red").failure(2, "VALIDATION_ERROR");
        cli.run("add", "--title", "a", "--title", "b").failure(2, "VALIDATION_ERROR");
        cli.run("add", "--title", "   ").failure(2, "VALIDATION_ERROR");
        cli.run("get", "abc").failure(2, "VALIDATION_ERROR");
        cli.run("list", "--status", "BLOCKED").failure(2, "VALIDATION_ERROR");
        cli.run("list", "--sort", "title").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("exit 3 carries INVALID_TRANSITION with an empty stdout")
    void transitionError() {
        cli.run("add", "--title", "a").success();
        cli.run("move", "1", "DONE").failure(3, "INVALID_TRANSITION");
    }

    @Test
    @DisplayName("exit 4 carries NOT_FOUND with an empty stdout")
    void notFoundErrors() {
        cli.run("add", "--title", "a").success();
        cli.run("get", "99").failure(4, "NOT_FOUND");
        cli.run("update", "99", "--title", "x").failure(4, "NOT_FOUND");
        cli.run("delete", "99").failure(4, "NOT_FOUND");
        cli.run("move", "99", "DONE").failure(4, "NOT_FOUND");
    }

    @Test
    @DisplayName("exit 5 carries STORAGE_ERROR with an empty stdout")
    void storageErrors() throws IOException {
        Files.writeString(data, "{ this is not json");
        cli.run("list").failure(5, "STORAGE_ERROR");
        cli.run("add", "--title", "x").failure(5, "STORAGE_ERROR");
    }

    @Test
    @DisplayName("the error document has exactly the documented shape")
    void errorDocumentShape() {
        cli.run("get", "abc").failure(2, "VALIDATION_ERROR");
        assertTrue(cli.stderr().startsWith("{\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\""),
                cli.stderr());
        assertTrue(cli.stderr().endsWith("\"}}\n"), cli.stderr());
        assertEquals(2, cli.stderr().chars().filter(c -> c == '{').count(), "one error object only");
    }

    @Test
    @DisplayName("every exit code is one of the five documented values")
    void onlyDocumentedExitCodes() {
        for (String[] command : List.of(new String[] {"list"}, new String[] {"frobnicate"},
                new String[] {"get", "abc"}, new String[] {"get", "99"},
                new String[] {"add", "--title", "ok"})) {
            cli.run(command);
            assertTrue(List.of(0, 2, 3, 4, 5).contains(cli.exitCode()),
                    "undocumented exit code " + cli.exitCode() + " for " + command[0]);
        }
    }
}
