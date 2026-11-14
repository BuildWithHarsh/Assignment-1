package com.atria.todo.domain;

/**
 * How important a task is, with an explicit sort rank because the display order
 * ({@code HIGH}, {@code MEDIUM}, {@code LOW}) is neither alphabetical nor declaration order.
 *
 * <p>The rank is a declared field rather than {@code ordinal()}, so reordering the constants cannot
 * silently change the sort contract asserted by AC-11.</p>
 */
public enum Priority {

    /** Sorts last under {@code --sort priority}. */
    LOW(2),

    /** The default for a new task. */
    MEDIUM(1),

    /** Sorts first under {@code --sort priority}. */
    HIGH(0);

    private final int rank;

    Priority(int rank) {
        this.rank = rank;
    }

    /**
     * The sort rank: lower sorts first.
     *
     * @return {@code 0} for {@code HIGH}, {@code 1} for {@code MEDIUM}, {@code 2} for {@code LOW}
     */
    public int rank() {
        return rank;
    }

    /**
     * Parses a user-supplied priority, case-insensitively.
     *
     * @param raw the value as the user typed it
     * @return the matching constant
     * @throws TodoException {@code VALIDATION_ERROR} if the value names no constant; there is no
     *                       silent fallback to a default
     */
    public static Priority parse(String raw) {
        String candidate = CaseFold.upperAscii(raw == null ? "" : raw.trim());
        for (Priority priority : values()) {
            if (priority.name().equals(candidate)) {
                return priority;
            }
        }
        throw new TodoException(ErrorCode.VALIDATION_ERROR, "unknown priority: " + raw);
    }
}
