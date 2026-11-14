package com.atria.todo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atria.todo.cli.CliFixture;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Filtering and ordering over the four tasks the locked suite seeds (AC-08..AC-11).
 *
 * <p>{@code t1} LOW/home, {@code t2} HIGH/work, {@code t3} MEDIUM/work+home, {@code t4} HIGH/home.</p>
 */
class TaskQueryTest {

    @TempDir
    private Path directory;

    private CliFixture cli;

    @BeforeEach
    void setUp() {
        cli = new CliFixture(directory.resolve("tasks.json"));
        cli.run("add", "--title", "t1", "--priority", "LOW", "--tags", "home").success();
        cli.run("add", "--title", "t2", "--priority", "HIGH", "--tags", "work").success();
        cli.run("add", "--title", "t3", "--priority", "MEDIUM", "--tags", "work,home").success();
        cli.run("add", "--title", "t4", "--priority", "HIGH", "--tags", "home").success();
    }

    private static List<Integer> ids(JsonNode array) {
        List<Integer> ids = new ArrayList<>();
        for (JsonNode task : array) {
            ids.add(task.get("id").intValue());
        }
        return ids;
    }

    private List<Integer> list(String... args) {
        return ids(cli.run(args).success());
    }

    @Test
    @DisplayName("--tag matches case-insensitively (AC-09)")
    void tagFilterIsCaseInsensitive() {
        assertEquals(List.of(2, 3), list("list", "--tag", "WORK"));
        assertEquals(List.of(2, 3), list("list", "--tag", "work"));
        assertEquals(List.of(2, 3), list("list", "--tag", " Work "));
        assertEquals(List.of(1, 3, 4), list("list", "--tag", "home"));
    }

    @Test
    @DisplayName("a tag matching nothing is an empty array at exit 0, not NOT_FOUND (AC-09)")
    void emptyResultIsNotAnError() {
        assertEquals(List.of(), list("list", "--tag", "nothing"));
        assertEquals("[]", cli.run("list", "--tag", "nothing").success().toString());
    }

    @Test
    @DisplayName("--status returns only matching tasks and rejects an unknown value (AC-10)")
    void statusFilter() {
        cli.run("move", "2", "IN_PROGRESS").success();
        assertEquals(List.of(2), list("list", "--status", "IN_PROGRESS"));
        assertEquals(List.of(1, 3, 4), list("list", "--status", "TODO"));
        assertEquals(List.of(), list("list", "--status", "DONE"));
        assertEquals(List.of(2), list("list", "--status", "in_progress"));
        cli.run("list", "--status", "BLOCKED").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("--sort priority orders HIGH, MEDIUM, LOW with ties by id ascending (AC-11)")
    void prioritySort() {
        assertEquals(List.of(2, 4, 3, 1), list("list", "--sort", "priority"));
    }

    @Test
    @DisplayName("filters combine with AND and sorting is applied after filtering (AC-11)")
    void filtersCombineThenSort() {
        assertEquals(List.of(4, 3, 1), list("list", "--tag", "home", "--sort", "priority"));
        assertEquals(List.of(2, 3), list("list", "--tag", "work", "--status", "TODO", "--sort", "priority"));
        cli.run("move", "2", "IN_PROGRESS").success();
        assertEquals(List.of(2), list("list", "--tag", "work", "--status", "IN_PROGRESS"));
        assertEquals(List.of(), list("list", "--tag", "nothing", "--status", "TODO"));
    }

    @Test
    @DisplayName("no options means every task in id order (AC-08)")
    void noOptionsIsIdOrder() {
        assertEquals(List.of(1, 2, 3, 4), list("list"));
    }

    @Test
    @DisplayName("--sort accepts only priority")
    void sortValueIsRestricted() {
        cli.run("list", "--sort", "title").failure(2, "VALIDATION_ERROR");
        cli.run("list", "--sort", "id").failure(2, "VALIDATION_ERROR");
        cli.run("list", "--sort", "PRIORITY").failure(2, "VALIDATION_ERROR");
    }
}
