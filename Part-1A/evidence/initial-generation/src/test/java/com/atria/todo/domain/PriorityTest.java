package com.atria.todo.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Priority parsing and the explicit sort rank (FR-011, AC-03, AC-11). */
class PriorityTest {

    @Test
    @DisplayName("parsing is case-insensitive")
    void parsingIsCaseInsensitive() {
        assertEquals(Priority.HIGH, Priority.parse("high"));
        assertEquals(Priority.HIGH, Priority.parse("HIGH"));
        assertEquals(Priority.MEDIUM, Priority.parse("Medium"));
        assertEquals(Priority.LOW, Priority.parse("lOw"));
    }

    @Test
    @DisplayName("output is upper-case")
    void nameIsUpperCase() {
        assertEquals("HIGH", Priority.parse("high").name());
    }

    @Test
    @DisplayName("an unknown value is a VALIDATION_ERROR naming the offending value")
    void unknownValueRejected() {
        TodoException thrown = assertThrows(TodoException.class, () -> Priority.parse("urgent"));
        assertEquals(ErrorCode.VALIDATION_ERROR, thrown.code());
        assertTrue(thrown.getMessage().contains("urgent"), "message should carry the offending value");
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(TodoException.class, () -> Priority.parse(null)).code());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(TodoException.class, () -> Priority.parse("")).code());
    }

    @Test
    @DisplayName("rank order is HIGH < MEDIUM < LOW")
    void rankOrder() {
        assertEquals(0, Priority.HIGH.rank());
        assertEquals(1, Priority.MEDIUM.rank());
        assertEquals(2, Priority.LOW.rank());
        assertTrue(Priority.HIGH.rank() < Priority.MEDIUM.rank());
        assertTrue(Priority.MEDIUM.rank() < Priority.LOW.rank());
    }
}
