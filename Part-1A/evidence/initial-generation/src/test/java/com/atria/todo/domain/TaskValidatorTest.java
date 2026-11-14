package com.atria.todo.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Title and tag normalisation rules (FR-010, FR-013, AC-02, AC-04). */
class TaskValidatorTest {

    @Test
    @DisplayName("title is trimmed")
    void titleIsTrimmed() {
        assertEquals("Pay rent", TaskValidator.validateTitle("   Pay rent  "));
    }

    @Test
    @DisplayName("blank and whitespace-only titles are rejected")
    void blankTitleRejected() {
        TodoException blank = assertThrows(TodoException.class, () -> TaskValidator.validateTitle("    "));
        assertEquals(ErrorCode.VALIDATION_ERROR, blank.code());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(TodoException.class, () -> TaskValidator.validateTitle("")).code());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(TodoException.class, () -> TaskValidator.validateTitle(null)).code());
    }

    @Test
    @DisplayName("200 characters accepted, 201 rejected, measured after trimming")
    void titleLengthBoundary() {
        String exactly200 = "y".repeat(200);
        assertEquals(exactly200, TaskValidator.validateTitle(exactly200));
        assertEquals(exactly200, TaskValidator.validateTitle("   " + exactly200 + "   "));
        TodoException tooLong = assertThrows(TodoException.class,
                () -> TaskValidator.validateTitle("x".repeat(201)));
        assertEquals(ErrorCode.VALIDATION_ERROR, tooLong.code());
    }

    @Test
    @DisplayName("tags are trimmed, lower-cased, de-duplicated, emptied entries dropped, sorted")
    void tagsAreNormalised() {
        assertEquals(List.of("home", "urgent", "work"),
                TaskValidator.normaliseTags(" Work,home,WORK,, urgent "));
    }

    @Test
    @DisplayName("a tag value that collapses to nothing yields an empty list, not an error")
    void tagsCollapsingToNothing() {
        assertEquals(List.of(), TaskValidator.normaliseTags(" , ,"));
        assertEquals(List.of(), TaskValidator.normaliseTags(""));
        assertEquals(List.of(), TaskValidator.normaliseTags(null));
    }

    @Test
    @DisplayName("the returned tag list is immutable")
    void tagListIsImmutable() {
        List<String> tags = TaskValidator.normaliseTags("a,b");
        assertThrows(UnsupportedOperationException.class, () -> tags.add("c"));
        assertTrue(tags.contains("a"));
    }
}
