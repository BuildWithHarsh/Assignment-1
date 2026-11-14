package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code add} command: defaults, normalisation and validation (AC-01..AC-04). */
class AddCommandTest {

    @TempDir
    private Path directory;

    private CliFixture cli;

    @BeforeEach
    void setUp() {
        cli = new CliFixture(directory.resolve("tasks.json"));
    }

    @Test
    @DisplayName("add with only --title uses the documented defaults (AC-01)")
    void defaults() {
        JsonNode task = cli.run("add", "--title", "Buy milk").success();
        assertEquals(1, task.get("id").intValue());
        assertEquals("Buy milk", task.get("title").textValue());
        assertEquals("MEDIUM", task.get("priority").textValue());
        assertEquals("TODO", task.get("status").textValue());
        assertTrue(task.get("tags").isArray());
        assertEquals(0, task.get("tags").size());
        assertTrue(task.get("createdAt").isTextual(), "createdAt must be a string, not a number");
        assertTrue(task.get("createdAt").textValue().endsWith("Z"), "createdAt must end in Z");
    }

    @Test
    @DisplayName("the six fields are emitted in the documented order")
    void fieldOrder() {
        cli.run("add", "--title", "Buy milk").success();
        assertTrue(cli.stdout().startsWith("{\"id\":1,\"title\":\"Buy milk\",\"priority\":\"MEDIUM\","
                + "\"status\":\"TODO\",\"tags\":[],\"createdAt\":\""), cli.stdout());
    }

    @Test
    @DisplayName("the title is trimmed (AC-02)")
    void titleTrimmed() {
        assertEquals("Pay rent", cli.run("add", "--title", "   Pay rent  ").success()
                .get("title").textValue());
    }

    @Test
    @DisplayName("--title is required")
    void titleRequired() {
        cli.run("add").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("a blank title is rejected and no task is created (AC-02)")
    void blankTitleRejected() {
        cli.run("add", "--title", "    ").failure(2, "VALIDATION_ERROR");
        assertEquals(0, cli.run("list").success().size(), "a rejected add must create nothing");
    }

    @Test
    @DisplayName("200 characters accepted, 201 rejected (AC-02 boundary)")
    void titleLengthBoundary() {
        cli.run("add", "--title", "x".repeat(201)).failure(2, "VALIDATION_ERROR");
        assertEquals("y".repeat(200),
                cli.run("add", "--title", "y".repeat(200)).success().get("title").textValue());
    }

    @Test
    @DisplayName("--priority is case-insensitive and stored upper-case (AC-03)")
    void priorityParsing() {
        assertEquals("HIGH", cli.run("add", "--title", "a", "--priority", "high").success()
                .get("priority").textValue());
        assertEquals("LOW", cli.run("add", "--title", "b", "--priority", "LOW").success()
                .get("priority").textValue());
    }

    @Test
    @DisplayName("an unknown priority is rejected and names the offending value (AC-03)")
    void unknownPriorityRejected() {
        cli.run("add", "--title", "a", "--priority", "urgent").failure(2, "VALIDATION_ERROR");
        assertTrue(cli.errorMessage().contains("urgent"), cli.errorMessage());
    }

    @Test
    @DisplayName("tags are trimmed, lower-cased, de-duplicated and sorted (AC-04)")
    void tagNormalisation() {
        JsonNode task = cli.run("add", "--title", "a", "--tags", " Work,home,WORK,, urgent ").success();
        assertEquals("[\"home\",\"urgent\",\"work\"]", task.get("tags").toString());
    }

    @Test
    @DisplayName("a tag value that collapses to nothing yields an empty list, not an error")
    void tagsCollapsingToNothing() {
        assertEquals(0, cli.run("add", "--title", "a", "--tags", " , ,").success().get("tags").size());
    }

    @Test
    @DisplayName("ids increase by one per created task")
    void idsIncrease() {
        assertEquals(1, cli.run("add", "--title", "a").success().get("id").intValue());
        assertEquals(2, cli.run("add", "--title", "b").success().get("id").intValue());
        assertEquals(3, cli.run("add", "--title", "c").success().get("id").intValue());
    }
}
