package com.atria.todo.domain;

/**
 * The failure classes the tool can report, each paired with the process exit status it maps to.
 *
 * <p>This enum is the single source of the code-to-exit mapping, so the documented exit code and the
 * {@code code} field of the error document on stderr cannot disagree.</p>
 */
public enum ErrorCode {

    /** Bad arguments, bad values, unknown command, unknown or repeated option, non-integer id. */
    VALIDATION_ERROR(2),

    /** A known status that is not a legal move from the task's current status. */
    INVALID_TRANSITION(3),

    /** No task with the requested id. */
    NOT_FOUND(4),

    /** Unreadable, unwritable, malformed, zero-byte or wrong-shaped store; failed write. */
    STORAGE_ERROR(5);

    private final int exitCode;

    ErrorCode(int exitCode) {
        this.exitCode = exitCode;
    }

    /**
     * The process exit status for this failure class.
     *
     * @return the exit code
     */
    public int exitCode() {
        return exitCode;
    }
}
