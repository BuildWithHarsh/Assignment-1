package com.atria.todo.domain;

/**
 * Where a task sits in its lifecycle. No rank: the order is expressed by the transition table in
 * {@link Transitions}, not by a comparison.
 *
 * <p>An unrecognised status word is a {@code VALIDATION_ERROR} (exit 2), never an
 * {@code INVALID_TRANSITION} (exit 3) — an unknown <em>word</em> is a validation fault while an
 * unreachable <em>state</em> is a transition fault (FR-006).</p>
 */
public enum Status {

    /** Not started. */
    TODO,

    /** Being worked on. */
    IN_PROGRESS,

    /** Finished. */
    DONE;

    /**
     * Parses a user-supplied status, case-insensitively.
     *
     * @param raw the value as the user typed it
     * @return the matching constant
     * @throws TodoException {@code VALIDATION_ERROR} if the value names no constant
     */
    public static Status parse(String raw) {
        String candidate = CaseFold.upperAscii(raw == null ? "" : raw.trim());
        for (Status status : values()) {
            if (status.name().equals(candidate)) {
                return status;
            }
        }
        throw new TodoException(ErrorCode.VALIDATION_ERROR, "unknown status: " + raw);
    }
}
