package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Error message wording: fixed per failure, carrying the user's offending value where one exists, and
 * nothing machine-specific (FR-003a, FR-034, FR-037).
 *
 * <p>The locked contract suite asserts only {@code error.code}, never the message text, so this test
 * is the only thing standing behind the wording decision.</p>
 */
class ErrorMessageTest {

    @TempDir
    private Path directory;

    private Path data;
    private CliFixture cli;

    @BeforeEach
    void setUp() {
        data = directory.resolve("tasks.json");
        cli = new CliFixture(data);
    }

    private static final List<String[]> FAILURES = List.of(
            new String[] {"frobnicate"},
            new String[] {"add"},
            new String[] {"add", "--title", "   "},
            new String[] {"add", "--title", "x", "--priority", "urgent"},
            new String[] {"add", "--title", "a", "--title", "b"},
            new String[] {"add", "--title", "x", "--colour", "red"},
            new String[] {"get", "abc"},
            new String[] {"get", "99"},
            new String[] {"get", "1", "2"},
            new String[] {"update", "1"},
            new String[] {"list", "--status", "BLOCKED"},
            new String[] {"list", "--sort", "title"},
            new String[] {"move", "1", "FINISHED"});

    @Test
    @DisplayName("the same failure produces the same message text on every run")
    void messagesAreRepeatable() {
        cli.run("add", "--title", "seed").success();
        List<String> first = new ArrayList<>();
        for (String[] command : FAILURES) {
            cli.run(command);
            first.add(cli.errorMessage());
        }
        List<String> second = new ArrayList<>();
        for (String[] command : FAILURES) {
            cli.run(command);
            second.add(cli.errorMessage());
        }
        assertEquals(first, second, "error wording must be fixed per failure");
    }

    @Test
    @DisplayName("no message carries a file path, system error text, stack trace or timestamp")
    void messagesCarryNothingMachineSpecific() throws IOException {
        List<String[]> commands = new ArrayList<>(FAILURES);
        commands.add(new String[] {"move", "1", "DONE"});
        cli.run("add", "--title", "seed").success();
        for (String[] command : commands) {
            cli.run(command);
            String message = cli.errorMessage();
            assertFalse(message.contains(directory.toString()), "leaks the directory: " + message);
            assertFalse(message.contains("tasks.json"), "leaks the file name: " + message);
            assertFalse(message.contains(".json"), "leaks a path: " + message);
            assertFalse(message.contains("Exception") || message.contains("\tat ")
                    || message.contains("com.atria") || message.contains("com.fasterxml"),
                    "leaks implementation detail: " + message);
            assertFalse(message.contains("202") && message.contains(":"), "leaks a timestamp: " + message);
            assertEquals(message.trim(), message, "message must not be padded");
            assertFalse(message.isEmpty(), "message must not be empty");
        }
    }

    @Test
    @DisplayName("a storage failure says nothing about the file or the parser")
    void storageMessageSaysNothingRevealing() throws IOException {
        Files.writeString(data, "{ this is not json");
        cli.run("list").failure(5, "STORAGE_ERROR");
        String message = cli.errorMessage();
        assertFalse(message.contains("this is not json"), "leaks file content: " + message);
        assertFalse(message.contains("token") || message.contains("Unrecognized")
                || message.contains("line") || message.contains("column"),
                "leaks parser text: " + message);
        assertEquals(message, new CliFixture(data).run("list").errorMessage(),
                "the same damaged store must produce the same message");
    }

    @Test
    @DisplayName("the offending value appears in the message where the user supplied one")
    void offendingValueIsNamed() {
        cli.run("add", "--title", "seed").success();
        assertTrue(cli.run("add", "--title", "x", "--priority", "urgent").errorMessage().contains("urgent"));
        assertTrue(cli.run("move", "1", "FINISHED").errorMessage().contains("FINISHED"));
        assertTrue(cli.run("frobnicate").errorMessage().contains("frobnicate"));
        assertTrue(cli.run("add", "--title", "x", "--colour", "red").errorMessage().contains("--colour"));
        assertTrue(cli.run("get", "abc").errorMessage().contains("abc"));
        assertTrue(cli.run("get", "99").errorMessage().contains("99"));
        assertTrue(cli.run("list", "--status", "BLOCKED").errorMessage().contains("BLOCKED"));
    }

    @Test
    @DisplayName("an identical command sequence produces identical stderr bytes")
    void stderrIsDeterministic() {
        CliFixture first = new CliFixture(directory.resolve("one.json"));
        CliFixture second = new CliFixture(directory.resolve("two.json"));
        first.run("add", "--title", "a").success();
        second.run("add", "--title", "a").success();
        for (String[] command : FAILURES) {
            first.run(command);
            second.run(command);
            assertEquals(first.stderr(), second.stderr(), "stderr must not vary by data file");
            assertEquals(first.exitCode(), second.exitCode());
        }
    }
}
