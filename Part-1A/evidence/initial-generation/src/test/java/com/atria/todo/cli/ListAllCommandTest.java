package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bare {@code list}: the missing-file rule and default ordering (AC-08, AC-14). */
class ListAllCommandTest {

    @TempDir
    private Path directory;

    private Path data;
    private CliFixture cli;

    @BeforeEach
    void setUp() {
        data = directory.resolve("tasks.json");
        cli = new CliFixture(data);
    }

    private static List<Integer> ids(JsonNode array) {
        List<Integer> ids = new ArrayList<>();
        for (JsonNode task : array) {
            ids.add(task.get("id").intValue());
        }
        return ids;
    }

    @Test
    @DisplayName("list against a missing data file prints [] and creates nothing (AC-14)")
    void missingFileIsEmptyList() {
        JsonNode result = cli.run("list").success();
        assertEquals("[]", result.toString());
        assertEquals("[]\n", cli.stdout());
        assertFalse(Files.exists(data), "a read must not create the data file");
    }

    @Test
    @DisplayName("list returns every task in id-ascending order (AC-08)")
    void idAscendingOrder() {
        for (String title : List.of("a", "b", "c", "d")) {
            cli.run("add", "--title", title).success();
        }
        assertEquals(List.of(1, 2, 3, 4), ids(cli.run("list").success()));
    }

    @Test
    @DisplayName("list takes no positional argument")
    void noPositionals() {
        cli.run("list", "1").failure(2, "VALIDATION_ERROR");
    }
}
