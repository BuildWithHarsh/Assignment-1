package com.atria.reminder.cli;

/** Process exit codes fixed by spec.md IR-03..IR-05. Nothing else may be returned. */
public final class ExitCode {

    /** One valid decision written to stdout. */
    public static final int OK = 0;

    /** An unexpected internal fault — an application bug, never an environment problem. */
    public static final int INTERNAL_ERROR = 1;

    /** Bad or missing arguments, or unparseable configuration. */
    public static final int VALIDATION_ERROR = 2;

    /** Genuine model or service unavailability. MUST NEVER be used for an application bug. */
    public static final int MODEL_UNAVAILABLE = 6;

    /** Model output still invalid after exactly one retry. */
    public static final int MODEL_OUTPUT_INVALID = 7;

    private ExitCode() {
    }
}
