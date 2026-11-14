package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code get} command, including persistence across invocations (AC-06, AC-13). */
class GetCommandTest {

    @TempDir
    private Path directory;

    private CliFixture cli;

    @BeforeEach
    void setUp() {
        cli = new CliFixture(directory.resolve("tasks.json"));
    }

    @Test
    @DisplayName("get returns the stored task, unchanged, in a later invocation (AC-13)")
    void returnsStoredTask() {
        JsonNode created = cli.run("add", "--title", "persist me", "--tags", "x").success();
        JsonNode read = cli.run("get", "1").success();
        assertEquals(created.toString(), read.toString(), "a later invocation must see the same task");
        assertEquals("persist me", read.get("title").textValue());
    }

    @Test
    @DisplayName("an unknown id is NOT_FOUND (AC-06)")
    void unknownIdIsNotFound() {
        cli.run("add", "--title", "a").success();
        cli.run("get", "99").failure(4, "NOT_FOUND");
        assertTrue(cli.errorMessage().contains("99"), cli.errorMessage());
    }

    @Test
    @DisplayName("a non-integer id is a VALIDATION_ERROR (AC-06)")
    void nonIntegerIdIsValidationError() {
        cli.run("add", "--title", "a").success();
        cli.run("get", "abc").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("get against a missing data file is NOT_FOUND, not a storage failure")
    void missingStoreIsNotFound() {
        cli.run("get", "1").failure(4, "NOT_FOUND");
    }

    @Test
    @DisplayName("get with a missing or extra positional argument is a VALIDATION_ERROR")
    void wrongArity() {
        cli.run("get").failure(2, "VALIDATION_ERROR");
        cli.run("get", "1", "2").failure(2, "VALIDATION_ERROR");
    }
}
