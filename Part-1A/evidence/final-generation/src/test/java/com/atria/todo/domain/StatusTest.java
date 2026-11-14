package com.atria.todo.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Status parsing (FR-012, AC-10, AC-12). */
class StatusTest {

    @Test
    @DisplayName("all three constants parse case-insensitively")
    void parsingIsCaseInsensitive() {
        assertEquals(Status.TODO, Status.parse("todo"));
        assertEquals(Status.TODO, Status.parse("TODO"));
        assertEquals(Status.IN_PROGRESS, Status.parse("in_progress"));
        assertEquals(Status.IN_PROGRESS, Status.parse("In_Progress"));
        assertEquals(Status.DONE, Status.parse("done"));
        assertEquals(Status.DONE, Status.parse("DONE"));
    }

    @Test
    @DisplayName("unknown status words are VALIDATION_ERROR, not INVALID_TRANSITION")
    void unknownWordsRejected() {
        for (String word : new String[] {"BLOCKED", "FINISHED", "in progress", "", null}) {
            TodoException thrown = assertThrows(TodoException.class, () -> Status.parse(word));
            assertEquals(ErrorCode.VALIDATION_ERROR, thrown.code(), "rejecting " + word);
        }
    }
}
