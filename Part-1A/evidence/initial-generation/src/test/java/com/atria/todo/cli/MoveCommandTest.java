package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code move} command, including the 2-versus-3 split (AC-06, AC-12, FR-006). */
class MoveCommandTest {

    @TempDir
    private Path directory;

    private CliFixture cli;

    @BeforeEach
    void setUp() {
        cli = new CliFixture(directory.resolve("tasks.json"));
        cli.run("add", "--title", "a").success();
    }

    @Test
    @DisplayName("the full legal walk succeeds and each step prints the new status (AC-12)")
    void legalWalk() {
        for (String target : List.of("IN_PROGRESS", "TODO", "IN_PROGRESS", "DONE", "TODO")) {
            assertEquals(target, cli.run("move", "1", target).success().get("status").textValue(),
                    "move 1 " + target);
        }
    }

    @Test
    @DisplayName("status words are accepted case-insensitively")
    void caseInsensitiveStatus() {
        assertEquals("IN_PROGRESS", cli.run("move", "1", "in_progress").success().get("status").textValue());
    }

    @Test
    @DisplayName("TODO -> DONE is refused and the stored task is unchanged (AC-12)")
    void illegalJumpLeavesTaskUntouched() {
        cli.run("move", "1", "DONE").failure(3, "INVALID_TRANSITION");
        assertEquals("TODO", cli.run("get", "1").success().get("status").textValue());
    }

    @Test
    @DisplayName("a move to the current status is refused (AC-12)")
    void sameStatusRefused() {
        cli.run("move", "1", "TODO").failure(3, "INVALID_TRANSITION");
        cli.run("move", "1", "IN_PROGRESS").success();
        cli.run("move", "1", "IN_PROGRESS").failure(3, "INVALID_TRANSITION");
    }

    @Test
    @DisplayName("DONE -> IN_PROGRESS is refused (AC-12)")
    void reopeningGoesThroughTodo() {
        cli.run("move", "1", "IN_PROGRESS").success();
        cli.run("move", "1", "DONE").success();
        cli.run("move", "1", "IN_PROGRESS").failure(3, "INVALID_TRANSITION");
        assertEquals("DONE", cli.run("get", "1").success().get("status").textValue());
    }

    @Test
    @DisplayName("an unknown status word is exit 2, not exit 3 (FR-006)")
    void unknownStatusWordIsValidationError() {
        cli.run("move", "1", "FINISHED").failure(2, "VALIDATION_ERROR");
        cli.run("move", "1", "BLOCKED").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("an unknown id is NOT_FOUND (AC-06)")
    void unknownIdIsNotFound() {
        cli.run("move", "99", "DONE").failure(4, "NOT_FOUND");
    }

    @Test
    @DisplayName("an unknown status word beats an unknown id: exit 2, not 4")
    void validationBeatsLookup() {
        cli.run("move", "99", "FINISHED").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("a non-integer id is a VALIDATION_ERROR")
    void nonIntegerId() {
        cli.run("move", "abc", "DONE").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("move needs exactly two positional arguments")
    void arity() {
        cli.run("move", "1").failure(2, "VALIDATION_ERROR");
        cli.run("move", "1", "DONE", "extra").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("only the status changes; every other field is untouched")
    void onlyStatusChanges() {
        String before = cli.run("get", "1").success().toString();
        String after = cli.run("move", "1", "IN_PROGRESS").success().toString();
        assertEquals(before.replace("\"status\":\"TODO\"", "\"status\":\"IN_PROGRESS\""), after);
    }
}
