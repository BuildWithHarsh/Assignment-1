package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code update} command: only the named fields change (AC-07, FR-009, FR-023, FR-024). */
class UpdateCommandTest {

    @TempDir
    private Path directory;

    private CliFixture cli;

    @BeforeEach
    void setUp() {
        cli = new CliFixture(directory.resolve("tasks.json"));
        cli.run("add", "--title", "Write report", "--tags", "work").success();
    }

    @Test
    @DisplayName("update --priority changes only the priority, createdAt included (AC-07)")
    void onlyPriorityChanges() {
        JsonNode before = cli.run("get", "1").success();
        JsonNode after = cli.run("update", "1", "--priority", "HIGH").success();
        assertEquals("HIGH", after.get("priority").textValue());
        assertEquals(before.get("title").textValue(), after.get("title").textValue());
        assertEquals(before.get("status").textValue(), after.get("status").textValue());
        assertEquals(before.get("tags").toString(), after.get("tags").toString());
        assertEquals(before.get("createdAt").textValue(), after.get("createdAt").textValue(),
                "createdAt is set once at creation and never altered");
        assertEquals(before.get("id").intValue(), after.get("id").intValue());
    }

    @Test
    @DisplayName("the change is visible to a later invocation (AC-07, AC-13)")
    void changePersists() {
        cli.run("update", "1", "--priority", "HIGH").success();
        assertEquals("HIGH", cli.run("get", "1").success().get("priority").textValue());
    }

    @Test
    @DisplayName("update --title trims and validates exactly as add does")
    void titleRules() {
        assertEquals("Pay rent", cli.run("update", "1", "--title", "  Pay rent ").success()
                .get("title").textValue());
        cli.run("update", "1", "--title", "   ").failure(2, "VALIDATION_ERROR");
        cli.run("update", "1", "--title", "x".repeat(201)).failure(2, "VALIDATION_ERROR");
        assertEquals("Pay rent", cli.run("get", "1").success().get("title").textValue(),
                "a rejected update must change nothing");
    }

    @Test
    @DisplayName("update --tags replaces the whole set and normalises it (AC-04, AC-07)")
    void tagsAreReplacedNotMerged() {
        JsonNode updated = cli.run("update", "1", "--tags", " B,a,b ").success();
        assertEquals("[\"a\",\"b\"]", updated.get("tags").toString());
        assertEquals("[]", cli.run("update", "1", "--tags", " , ").success().get("tags").toString());
    }

    @Test
    @DisplayName("update with no field option is a VALIDATION_ERROR (AC-07)")
    void noFieldsRejected() {
        cli.run("update", "1").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("update against an unknown id is NOT_FOUND")
    void unknownIdIsNotFound() {
        cli.run("update", "99", "--title", "x").failure(4, "NOT_FOUND");
    }

    @Test
    @DisplayName("update cannot change the status")
    void statusIsNotUpdatable() {
        cli.run("update", "1", "--status", "DONE").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("several fields can be changed in one call")
    void severalFieldsAtOnce() {
        JsonNode updated = cli.run("update", "1", "--title", "New", "--priority", "LOW", "--tags", "z,y")
                .success();
        assertEquals("New", updated.get("title").textValue());
        assertEquals("LOW", updated.get("priority").textValue());
        assertEquals("[\"y\",\"z\"]", updated.get("tags").toString());
    }

    @Test
    @DisplayName("an invalid priority is rejected and names the offending value")
    void invalidPriority() {
        cli.run("update", "1", "--priority", "urgent").failure(2, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("update needs exactly one positional id")
    void arity() {
        cli.run("update", "--title", "x").failure(2, "VALIDATION_ERROR");
        cli.run("update", "1", "2", "--title", "x").failure(2, "VALIDATION_ERROR");
        cli.run("update", "abc", "--title", "x").failure(2, "VALIDATION_ERROR");
    }
}
