package com.atria.reminder.guard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The deterministic pre-model guard (FR-11, FR-13, FR-14). Applies the spec's precedence order:
 * EMPTY_INPUT, then INPUT_TOO_LONG, then SECRET, then INJECTION.
 *
 * <p>This class never makes a model call, so blank, over-long and credential-bearing input never leaves the process.
 */
public final class InputGuard {

    public static final int MAX_INPUT_CHARS = 500;

    /** Known injection phrasings. The model's own safety flag covers wordings not listed here. */
    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("\\bignore\\b.{0,40}\\b(instructions?|prompt|rules?|above|previous|prior)\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("\\b(disregard|forget|override|bypass)\\b.{0,40}\\b(instructions?|prompt|rules?)\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("\\b(system|developer)\\s+prompt\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(reveal|print|show|repeat|output|tell me)\\b.{0,40}"
                    + "\\b(instructions?|prompt|system message|rules)\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("\\byou are now\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnew instructions?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(internal )?marker\\b.{0,20}\\bRMDR\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("RMDR-CANARY", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcanary\\b", Pattern.CASE_INSENSITIVE));

    private InputGuard() {
    }

    public static GuardVerdict inspect(String input) {
        if (input == null || input.isBlank()) {
            return new GuardVerdict(GuardVerdict.Outcome.EMPTY_INPUT, "input is blank");
        }
        if (input.length() > MAX_INPUT_CHARS) {
            return new GuardVerdict(GuardVerdict.Outcome.INPUT_TOO_LONG,
                    "input is " + input.length() + " characters, limit " + MAX_INPUT_CHARS);
        }
        String secret = SecretDetector.detect(input);
        if (secret != null) {
            return new GuardVerdict(GuardVerdict.Outcome.SECRET, "input contains " + secret);
        }
        for (Pattern p : INJECTION) {
            if (p.matcher(input).find()) {
                return new GuardVerdict(GuardVerdict.Outcome.INJECTION, "input matches a known injection phrasing");
            }
        }
        return GuardVerdict.PROCEED;
    }
}
