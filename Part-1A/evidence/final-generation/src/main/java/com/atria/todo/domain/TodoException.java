package com.atria.todo.domain;

/**
 * The single failure carrier for the whole program: one {@link ErrorCode} plus the message that goes
 * into the error document on stderr.
 *
 * <p>The message must be fixed wording per failure, optionally suffixed with the user's offending
 * value. It must never contain a file path, system or parser error text, a stack trace or a timestamp,
 * so that the same command produces the same error document on any machine (FR-003a, FR-034, FR-037).</p>
 */
public final class TodoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    /**
     * Creates a failure.
     *
     * @param code    the failure class, which decides the exit status
     * @param message fixed wording, optionally suffixed with the user's offending value
     */
    public TodoException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * The failure class.
     *
     * @return the error code
     */
    public ErrorCode code() {
        return code;
    }
}
