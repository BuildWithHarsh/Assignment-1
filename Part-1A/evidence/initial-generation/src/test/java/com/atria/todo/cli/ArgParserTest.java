package com.atria.todo.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.TodoException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Argument faults, every one of which is a VALIDATION_ERROR (FR-001, FR-005, FR-005a, FR-014). */
class ArgParserTest {

    private static void expectValidationError(String... args) {
        TodoException thrown = assertThrows(TodoException.class, () -> ArgParser.parse(args),
                "expected a VALIDATION_ERROR for: " + String.join(" ", args));
        assertEquals(ErrorCode.VALIDATION_ERROR, thrown.code(), String.join(" ", args));
    }

    @Test
    @DisplayName("a well-formed invocation parses into command, options and positionals")
    void happyPath() {
        ParsedCommand parsed = ArgParser.parse(new String[] {
            "--data", "d.json", "add", "--title", "Buy milk", "--tags", "a,b"});
        assertEquals("add", parsed.command());
        assertEquals("d.json", parsed.option(ArgParser.DATA));
        assertEquals("Buy milk", parsed.option("--title"));
        assertEquals("a,b", parsed.option("--tags"));
        assertNull(parsed.option("--priority"));
        assertEquals(List.of(), parsed.positionals());
        assertEquals(3, parsed.options().size());
    }

    @Test
    @DisplayName("positional arguments are kept in order")
    void positionalsInOrder() {
        ParsedCommand parsed = ArgParser.parse(new String[] {"--data", "d.json", "move", "7", "DONE"});
        assertEquals(List.of("7", "DONE"), parsed.positionals());
    }

    @Test
    @DisplayName("--data is required")
    void dataRequired() {
        expectValidationError("list");
        expectValidationError();
        expectValidationError("--data");
    }

    @Test
    @DisplayName("--data must come before the command name")
    void dataMustPrecedeCommand() {
        expectValidationError("list", "--data", "d.json");
    }

    @Test
    @DisplayName("an unknown command is rejected")
    void unknownCommand() {
        expectValidationError("--data", "d.json", "frobnicate");
    }

    @Test
    @DisplayName("a missing command is rejected")
    void missingCommand() {
        expectValidationError("--data", "d.json");
    }

    @Test
    @DisplayName("an unknown option is rejected")
    void unknownOption() {
        expectValidationError("--data", "d.json", "add", "--title", "x", "--colour", "red");
        expectValidationError("--data", "d.json", "list", "--title", "x");
        expectValidationError("--colour", "red", "list");
    }

    @Test
    @DisplayName("a repeated option is rejected rather than letting one occurrence win")
    void repeatedOption() {
        expectValidationError("--data", "d.json", "add", "--title", "a", "--title", "b");
        expectValidationError("--data", "d.json", "add", "--title", "a", "--tags", "x", "--tags", "y");
        expectValidationError("--data", "a.json", "--data", "b.json", "list");
    }

    @Test
    @DisplayName("an option without a value is rejected")
    void optionWithoutValue() {
        expectValidationError("--data", "d.json", "add", "--title");
        expectValidationError("--data", "d.json", "list", "--tag");
    }

    @Test
    @DisplayName("an unexpected or missing positional argument is rejected")
    void wrongArity() {
        expectValidationError("--data", "d.json", "get", "1", "2");
        expectValidationError("--data", "d.json", "get");
        expectValidationError("--data", "d.json", "list", "1");
        expectValidationError("--data", "d.json", "add", "extra", "--title", "x");
        expectValidationError("--data", "d.json", "move", "1");
    }

    @Test
    @DisplayName("a non-integer id is rejected before any lookup")
    void nonIntegerId() {
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(TodoException.class, () -> ArgParser.parseId("abc")).code());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(TodoException.class, () -> ArgParser.parseId("1.5")).code());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(TodoException.class, () -> ArgParser.parseId("")).code());
        assertEquals(7, ArgParser.parseId("7"));
        assertEquals(7, ArgParser.parseId(" 7 "));
    }

    @Test
    @DisplayName("option names are matched case-sensitively")
    void optionNamesAreCaseSensitive() {
        expectValidationError("--data", "d.json", "add", "--Title", "x");
    }

    @Test
    @DisplayName("a control character in the data path is refused")
    void controlCharacterInDataPath() {
        TodoException thrown = assertThrows(TodoException.class, () -> ArgParser.dataPath("ta\u0000sks.json"));
        assertEquals(ErrorCode.STORAGE_ERROR, thrown.code());
    }

    @Test
    @DisplayName("a usable data path is normalised")
    void dataPathIsNormalised() {
        assertEquals("data/tasks.json",
                ArgParser.dataPath("data/./tasks.json").toString().replace('\\', '/'));
    }
}
