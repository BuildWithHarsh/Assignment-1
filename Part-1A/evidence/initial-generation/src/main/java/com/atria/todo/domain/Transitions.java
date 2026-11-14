package com.atria.todo.domain;

import java.util.List;

/**
 * The status state machine: exactly four legal moves, held as data rather than as nested conditions so
 * the table reads against AC-12 and stays inside the complexity ceilings.
 *
 * <p>{@code TODO -> DONE} is not allowed (work cannot skip the middle), {@code DONE -> IN_PROGRESS} is
 * not allowed (reopening goes through {@code TODO}), and a move to the task's current status is an
 * error rather than a silent no-op (FR-027).</p>
 */
public final class Transitions {

    private static final List<Status> FROM = List.of(Status.TODO, Status.IN_PROGRESS, Status.IN_PROGRESS,
            Status.DONE);
    private static final List<Status> TO = List.of(Status.IN_PROGRESS, Status.DONE, Status.TODO, Status.TODO);

    private Transitions() {
    }

    /**
     * Whether one status change is legal.
     *
     * @param from the task's current status
     * @param to   the requested status
     * @return {@code true} for one of the four legal moves, {@code false} for everything else
     */
    public static boolean allows(Status from, Status to) {
        for (int i = 0; i < FROM.size(); i++) {
            if (FROM.get(i) == from && TO.get(i) == to) {
                return true;
            }
        }
        return false;
    }
}
