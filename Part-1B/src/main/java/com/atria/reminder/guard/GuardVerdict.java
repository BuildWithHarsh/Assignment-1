package com.atria.reminder.guard;

/**
 * The pre-model guard outcome. {@code detail} is for stderr diagnostics only: it names which rule fired and must
 * never quote the matched value (FR-14).
 */
public record GuardVerdict(Outcome outcome, String detail) {

    public enum Outcome {
        PROCEED,
        EMPTY_INPUT,
        INPUT_TOO_LONG,
        SECRET,
        INJECTION
    }

    public static final GuardVerdict PROCEED = new GuardVerdict(Outcome.PROCEED, "");

    public boolean proceed() {
        return outcome == Outcome.PROCEED;
    }
}
