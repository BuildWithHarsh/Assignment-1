package com.atria.reminder;

import com.atria.reminder.cli.Args;
import com.atria.reminder.cli.ExitCode;
import com.atria.reminder.decision.DecisionMapper;
import com.atria.reminder.guard.GuardVerdict;
import com.atria.reminder.guard.InputGuard;
import com.atria.reminder.model.Extraction;
import com.atria.reminder.model.ExtractionException;
import com.atria.reminder.model.ExtractionParser;
import com.atria.reminder.model.ModelUnavailableException;
import com.atria.reminder.model.OllamaClient;
import com.atria.reminder.output.Decision;
import com.atria.reminder.output.ErrorEnvelope;
import com.atria.reminder.output.JsonPrinter;
import com.atria.reminder.output.OutputValidator;
import com.atria.reminder.prompt.PromptBuilder;
import com.atria.reminder.time.Resolution;
import com.atria.reminder.time.TimeResolver;
import java.time.Duration;
import java.util.List;

/**
 * Entry point. Wires the pipeline and maps outcomes to the exit codes fixed by spec.md IR-03..IR-05.
 *
 * <p>Nothing reaches stdout except one validated decision (INV-01); every diagnostic goes to stderr.
 */
public final class Main {

    /**
     * Wall-clock budget for the whole process. The harness kills a call at 120s and records it as an ERROR, which
     * makes the run INCONCLUSIVE, so the initial call plus one retry are capped below that and a clean exit 6 is
     * returned instead (research R9).
     */
    static final Duration PROCESS_BUDGET = Duration.ofSeconds(110);

    private static final int MAX_ATTEMPTS = 2;

    private Main() {
    }

    public static void main(String[] argv) {
        int code;
        try {
            code = run(argv);
        } catch (Throwable t) {
            // An application bug, never an environment problem: exit 1, never 6 (IR-05).
            ErrorEnvelope.write(ErrorEnvelope.INTERNAL_ERROR,
                    "unexpected internal fault: " + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " - " + t.getMessage()));
            code = ExitCode.INTERNAL_ERROR;
        }
        System.exit(code);
    }

    private static int run(String[] argv) {
        Args args;
        try {
            args = Args.parse(argv, System.getenv());
        } catch (Args.InvalidArgsException e) {
            ErrorEnvelope.write(ErrorEnvelope.VALIDATION_ERROR, e.getMessage());
            return ExitCode.VALIDATION_ERROR;
        }

        // FR-11, FR-13, FR-14 decided in code, before any prompt is built or any connection opened.
        GuardVerdict guard = InputGuard.inspect(args.input());
        if (!guard.proceed()) {
            return emit(DecisionMapper.fromGuard(guard), args);
        }

        PromptBuilder prompts = new PromptBuilder();
        OllamaClient client = new OllamaClient(args.ollamaUrl());
        long deadline = System.nanoTime() + PROCESS_BUDGET.toNanos();
        String correction = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String content;
            try {
                content = client.chat(prompts.body(args.model(), args.temperature(), args.input(), correction),
                        remaining(deadline));
            } catch (ModelUnavailableException e) {
                // Not retried: exit 6 must keep meaning "the service is down".
                ErrorEnvelope.write(ErrorEnvelope.MODEL_UNAVAILABLE, e.getMessage());
                return ExitCode.MODEL_UNAVAILABLE;
            }

            Extraction extraction;
            try {
                extraction = ExtractionParser.parse(content);
            } catch (ExtractionException e) {
                correction = e.getMessage();
                continue;
            }

            Resolution resolution = TimeResolver.resolve(extraction.time(), args.now());
            Decision decision = DecisionMapper.map(extraction, resolution, args.now());

            String json;
            try {
                json = JsonPrinter.toJson(decision);
            } catch (Exception e) {
                throw new IllegalStateException("decision could not be serialised", e);
            }
            List<String> errors = OutputValidator.validate(decision, args.now(), json);
            if (errors.isEmpty()) {
                JsonPrinter.print(json);
                return ExitCode.OK;
            }
            correction = errors.get(0);
        }

        ErrorEnvelope.write(ErrorEnvelope.MODEL_OUTPUT_INVALID,
                "model output was still invalid after one retry: " + correction);
        return ExitCode.MODEL_OUTPUT_INVALID;
    }

    /** A guard refusal still passes the validator — if it fails, that is our bug, so it surfaces as exit 1. */
    private static int emit(Decision decision, Args args) {
        String json;
        try {
            json = JsonPrinter.toJson(decision);
        } catch (Exception e) {
            throw new IllegalStateException("decision could not be serialised", e);
        }
        List<String> errors = OutputValidator.validate(decision, args.now(), json);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("internally built decision violates the output contract: " + errors);
        }
        JsonPrinter.print(json);
        return ExitCode.OK;
    }

    private static Duration remaining(long deadlineNanos) {
        Duration left = Duration.ofNanos(deadlineNanos - System.nanoTime());
        return left.compareTo(OllamaClient.CALL_TIMEOUT) > 0 ? OllamaClient.CALL_TIMEOUT : left;
    }
}
