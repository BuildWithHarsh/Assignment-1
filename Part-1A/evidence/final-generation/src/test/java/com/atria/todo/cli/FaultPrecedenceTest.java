package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fixed fault order of FR-006: arguments, then storage, then lookup, then transition. The first
 * failing check decides the exit code and later checks do not run, so one invocation always produces
 * one predictable code.
 *
 * <p>The locked contract suite tests one fault at a time, so it would pass an implementation that got
 * this order wrong; these cases are the only thing standing behind it.</p>
 */
class FaultPrecedenceTest {

    private static final String CORRUPT = "{ this is not json";

    @TempDir
    private Path directory;

    private Path corruptStore(String name) throws IOException {
        Path path = directory.resolve(name);
        Files.write(path, CORRUPT.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    @Test
    @DisplayName("update 99 with no field options is exit 2, not 4: arguments beat lookup")
    void argumentsBeatLookup() {
        CliFixture cli = new CliFixture(directory.resolve("tasks.json"));
        cli.run("add", "--title", "a").success();
        cli.run("update", "99").failure(2, "VALIDATION_ERROR");
        cli.run("update", "1").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("an unknown status word beats both lookup and the transition check")
    void validationBeatsTransition() {
        CliFixture cli = new CliFixture(directory.resolve("tasks.json"));
        cli.run("add", "--title", "a").success();
        cli.run("move", "1", "FINISHED").failure(2, "VALIDATION_ERROR");
        cli.run("move", "99", "FINISHED").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("a lookup failure beats the transition check")
    void lookupBeatsTransition() {
        CliFixture cli = new CliFixture(directory.resolve("tasks.json"));
        cli.run("add", "--title", "a").success();
        cli.run("move", "99", "DONE").failure(4, "NOT_FOUND");
    }

    @Test
    @DisplayName("a malformed argument against a corrupt data file is exit 2, not 5")
    void argumentsBeatStorage() throws IOException {
        CliFixture cli = new CliFixture(corruptStore("corrupt.json"));
        cli.run("frobnicate").failure(2, "VALIDATION_ERROR");
        cli.run("get", "abc").failure(2, "VALIDATION_ERROR");
        cli.run("add", "--title", "   ").failure(2, "VALIDATION_ERROR");
        cli.run("add", "--title", "a", "--priority", "urgent").failure(2, "VALIDATION_ERROR");
        cli.run("add", "--title", "a", "--title", "b").failure(2, "VALIDATION_ERROR");
        cli.run("add", "--title", "x", "--colour", "red").failure(2, "VALIDATION_ERROR");
        cli.run("update", "99").failure(2, "VALIDATION_ERROR");
        cli.run("list", "--status", "BLOCKED").failure(2, "VALIDATION_ERROR");
        cli.run("move", "1", "FINISHED").failure(2, "VALIDATION_ERROR");
        cli.run("delete").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("a command that fails an argument check never opens the data file")
    void argumentFaultsDoNotOpenTheStore() throws IOException {
        Path zeroByte = directory.resolve("empty.json");
        Files.write(zeroByte, new byte[0]);
        CliFixture cli = new CliFixture(zeroByte);
        for (String[] command : List.of(new String[] {"frobnicate"}, new String[] {"get", "abc"},
                new String[] {"update", "1"}, new String[] {"add", "--title", "  "},
                new String[] {"move", "1", "FINISHED"}, new String[] {"list", "--sort", "title"})) {
            cli.run(command).failure(2, "VALIDATION_ERROR");
        }
        assertEquals(0, Files.size(zeroByte), "the store must not have been touched at all");
    }

    @Test
    @DisplayName("a storage fault beats lookup: a corrupt store is exit 5 even for a missing id")
    void storageBeatsLookup() throws IOException {
        Path path = corruptStore("corrupt2.json");
        CliFixture cli = new CliFixture(path);
        cli.run("get", "99").failure(5, "STORAGE_ERROR");
        cli.run("delete", "99").failure(5, "STORAGE_ERROR");
        cli.run("update", "99", "--title", "x").failure(5, "STORAGE_ERROR");
        cli.run("move", "99", "DONE").failure(5, "STORAGE_ERROR");
        assertArrayEquals(CORRUPT.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path),
                "a damaged store is never repaired or overwritten");
    }
}
