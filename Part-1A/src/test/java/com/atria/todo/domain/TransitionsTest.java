package com.atria.todo.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The four legal status moves, and everything that is not one of them (FR-027, AC-12). */
class TransitionsTest {

    @Test
    @DisplayName("the four legal moves are allowed")
    void legalMoves() {
        assertTrue(Transitions.allows(Status.TODO, Status.IN_PROGRESS), "start work");
        assertTrue(Transitions.allows(Status.IN_PROGRESS, Status.DONE), "finish");
        assertTrue(Transitions.allows(Status.IN_PROGRESS, Status.TODO), "put back");
        assertTrue(Transitions.allows(Status.DONE, Status.TODO), "reopen");
    }

    @Test
    @DisplayName("work cannot skip the middle, and reopening goes through TODO")
    void illegalJumps() {
        assertFalse(Transitions.allows(Status.TODO, Status.DONE), "TODO -> DONE must be refused");
        assertFalse(Transitions.allows(Status.DONE, Status.IN_PROGRESS), "DONE -> IN_PROGRESS must be refused");
    }

    @Test
    @DisplayName("a move to the current status is an error, not a no-op")
    void sameStatusIsRefused() {
        for (Status status : Status.values()) {
            assertFalse(Transitions.allows(status, status), status + " -> " + status + " must be refused");
        }
    }

    @Test
    @DisplayName("exactly four of the nine pairings are legal")
    void exactlyFourLegalPairs() {
        int legal = 0;
        for (Status from : Status.values()) {
            for (Status to : Status.values()) {
                legal += Transitions.allows(from, to) ? 1 : 0;
            }
        }
        assertTrue(legal == 4, "expected 4 legal transitions, found " + legal);
    }
}
