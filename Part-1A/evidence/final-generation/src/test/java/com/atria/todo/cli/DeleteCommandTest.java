package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code delete} command and the never-reused id rule (AC-05, FR-025, FR-029a). */
class DeleteCommandTest {

    @TempDir
    private Path directory;

    private CliFixture cli;

    @BeforeEach
    void setUp() {
        cli = new CliFixture(directory.resolve("tasks.json"));
        for (String title : List.of("a", "b", "c")) {
            cli.run("add", "--title", title).success();
        }
    }

    private static List<Integer> ids(JsonNode array) {
        List<Integer> ids = new ArrayList<>();
        for (JsonNode task : array) {
            ids.add(task.get("id").intValue());
        }
        return ids;
    }

    @Test
    @DisplayName("delete prints exactly {\"deleted\": id} (AC-05)")
    void deleteOutputShape() {
        cli.run("delete", "3").success();
        assertEquals("{\"deleted\":3}\n", cli.stdout());
    }

    @Test
    @DisplayName("the task no longer appears in list")
    void taskIsGone() {
        cli.run("delete", "2").success();
        assertEquals(List.of(1, 3), ids(cli.run("list").success()));
        cli.run("get", "2").failure(4, "NOT_FOUND");
    }

    @Test
    @DisplayName("deleting the highest id does not free it: the next add is 4 (AC-05)")
    void deletedIdIsNeverReissued() {
        cli.run("delete", "3").success();
        assertEquals(4, cli.run("add", "--title", "d").success().get("id").intValue());
    }

    @Test
    @DisplayName("deleting every task leaves the counter in place")
    void counterSurvivesAnEmptyStore() {
        for (String id : List.of("1", "2", "3")) {
            cli.run("delete", id).success();
        }
        assertEquals("[]", cli.run("list").success().toString());
        assertEquals(4, cli.run("add", "--title", "d").success().get("id").intValue());
    }

    @Test
    @DisplayName("delete against an unknown id is NOT_FOUND")
    void unknownIdIsNotFound() {
        cli.run("delete", "99").failure(4, "NOT_FOUND");
        assertEquals(List.of(1, 2, 3), ids(cli.run("list").success()), "a failed delete changes nothing");
    }

    @Test
    @DisplayName("delete needs exactly one integer positional")
    void arity() {
        cli.run("delete").failure(2, "VALIDATION_ERROR");
        cli.run("delete", "1", "2").failure(2, "VALIDATION_ERROR");
        cli.run("delete", "abc").failure(2, "VALIDATION_ERROR");
    }
}
