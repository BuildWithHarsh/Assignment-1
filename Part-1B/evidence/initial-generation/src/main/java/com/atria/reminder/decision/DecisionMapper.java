package com.atria.reminder.decision;

import com.atria.reminder.guard.GuardVerdict;
import com.atria.reminder.model.Extraction;
import com.atria.reminder.output.Decision;
import com.atria.reminder.output.MissingField;
import com.atria.reminder.output.OutputValidator;
import com.atria.reminder.output.ReasonCode;
import com.atria.reminder.time.Resolution;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a guard verdict, the model's extraction and a time resolution into one Decision, applying the precedence
 * ladder fixed by spec.md:
 *
 * <pre>EMPTY_INPUT -> INPUT_TOO_LONG -> UNSAFE_REQUEST -> UNSUPPORTED_REQUEST -> clarify codes -> clarify gaps</pre>
 *
 * First match wins and no later rule overrides it. The model's safety flag may escalate to a refusal but never
 * downgrade one the guard already made.
 */
public final class DecisionMapper {

    public static final int MIN_SNOOZE = OutputValidator.MIN_SNOOZE;
    public static final int MAX_SNOOZE = OutputValidator.MAX_SNOOZE;

    private static final DateTimeFormatter WIRE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:'00'XXX", Locale.ENGLISH);
    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("d MMM yyyy 'at' h:mm a", Locale.ENGLISH);

    private DecisionMapper() {
    }

    /** The guard-only path: a refusal decided before any model call. */
    public static Decision fromGuard(GuardVerdict guard) {
        return switch (guard.outcome()) {
            case EMPTY_INPUT -> Decision.reject(ReasonCode.EMPTY_INPUT,
                    "I did not receive any text. Tell me what to remind you about and when.");
            case INPUT_TOO_LONG -> Decision.reject(ReasonCode.INPUT_TOO_LONG,
                    "That request is longer than the " + com.atria.reminder.guard.InputGuard.MAX_INPUT_CHARS
                            + " character limit. Please shorten it and try again.");
            case SECRET -> Decision.reject(ReasonCode.UNSAFE_REQUEST,
                    "I will not store credentials such as one-time codes, passwords, PINs or card numbers "
                            + "in a reminder. Ask me to remind you about something else.");
            case INJECTION -> Decision.reject(ReasonCode.UNSAFE_REQUEST,
                    "I can only help with reminders, and I will not act on instructions hidden in a request.");
            default -> throw new IllegalArgumentException("fromGuard called with " + guard.outcome());
        };
    }

    public static Decision map(Extraction ex, Resolution res, OffsetDateTime now) {
        // --- UNSAFE_REQUEST, then UNSUPPORTED_REQUEST (the guard's steps already ran in Main) ------------------
        if (ex.safety() == Extraction.Safety.INJECTION) {
            return Decision.reject(ReasonCode.UNSAFE_REQUEST,
                    "I can only help with reminders, and I will not act on instructions hidden in a request.");
        }
        if (ex.safety() == Extraction.Safety.SECRET) {
            return Decision.reject(ReasonCode.UNSAFE_REQUEST,
                    "I will not store credentials such as one-time codes, passwords, PINs or card numbers "
                            + "in a reminder. Ask me to remind you about something else.");
        }
        if (ex.safety() == Extraction.Safety.UNSUPPORTED || ex.action() == Extraction.Action.OTHER) {
            return Decision.reject(ReasonCode.UNSUPPORTED_REQUEST,
                    "I can only create a reminder, move one to a new time, or snooze one. I cannot do that.");
        }

        String task = cap(TaskText.clean(ex.task()), OutputValidator.MAX_TEXT);
        String target = cap(TaskText.clean(ex.target()), OutputValidator.MAX_TEXT);
        boolean wantsSnooze = ex.action() == Extraction.Action.SNOOZE;
        boolean wantsReschedule = ex.action() == Extraction.Action.RESCHEDULE;
        boolean existing = wantsSnooze || wantsReschedule;
        boolean targetVague = ex.targetVague();

        // For a reschedule or snooze the one piece of identifying text is the target, whichever field the model
        // happened to put it in. The action already says an existing reminder is meant, so a lone task here is
        // that reminder's description, not a new thing to be reminded about.
        if (existing && target == null && task != null) {
            target = task;
            targetVague = ex.taskVague();
            task = null;
        }
        boolean usableTarget = target != null && !targetVague && !TaskText.isVague(target);
        boolean usableTask = task != null && !ex.taskVague() && !TaskText.isVague(task);

        // --- clarify codes: a time was supplied and found unusable ---------------------------------------------
        if (wantsSnooze && ex.snoozeMinutes() != null
                && (ex.snoozeMinutes() < MIN_SNOOZE || ex.snoozeMinutes() > MAX_SNOOZE)) {
            return Decision.clarify(null, keepTarget(target, true), List.of(MissingField.TIME),
                    ReasonCode.SNOOZE_OUT_OF_RANGE,
                    question("I can snooze between " + MIN_SNOOZE + " and " + MAX_SNOOZE
                            + " minutes. How long should I snooze " + reference(target) + " for"));
        }
        if (res instanceof Resolution.Past) {
            return Decision.clarify(keepTask(task, existing),
                    keepTarget(target, existing),
                    List.of(MissingField.TIME), ReasonCode.PAST_TIME,
                    question("That time has already passed. When should I remind you instead"));
        }
        if (res instanceof Resolution.Invalid) {
            return Decision.clarify(keepTask(task, existing),
                    keepTarget(target, existing),
                    List.of(MissingField.TIME), ReasonCode.INVALID_TIME,
                    question("That date or time does not exist. When should I remind you"));
        }
        if (res instanceof Resolution.OutOfHorizon) {
            return Decision.clarify(keepTask(task, existing),
                    keepTarget(target, existing),
                    List.of(MissingField.TIME), ReasonCode.INVALID_TIME,
                    question("I can only set reminders up to a year ahead. When should I remind you"));
        }

        // --- clarify gaps: FR-07, FR-08, FR-09, no reason code -------------------------------------------------
        boolean timeMissing = res instanceof Resolution.Absent || res instanceof Resolution.Vague;
        List<MissingField> gaps = new ArrayList<>();
        if (wantsSnooze) {
            if (!usableTarget) {
                gaps.add(MissingField.TARGET);
            }
            if (ex.snoozeMinutes() == null) {
                gaps.add(MissingField.TIME);
            }
        } else if (wantsReschedule) {
            if (!usableTarget) {
                gaps.add(MissingField.TARGET);
            }
            if (timeMissing) {
                gaps.add(MissingField.TIME);
            }
        } else {
            if (!usableTask) {
                gaps.add(MissingField.TASK);
            }
            if (timeMissing) {
                gaps.add(MissingField.TIME);
            }
        }

        if (!gaps.isEmpty()) {
            String keptTask = gaps.contains(MissingField.TASK) ? null : keepTask(task, existing);
            String keptTarget = gaps.contains(MissingField.TARGET) ? null : keepTarget(target, existing);
            return Decision.clarify(keptTask, keptTarget, gaps, null, gapQuestion(gaps, keptTask, keptTarget,
                    wantsSnooze));
        }

        // --- the three actions ---------------------------------------------------------------------------------
        if (wantsSnooze) {
            int minutes = ex.snoozeMinutes();
            return Decision.snooze(target, minutes,
                    cap("Snoozed " + reference(target) + " for " + minutes + (minutes == 1 ? " minute." : " minutes."),
                            OutputValidator.MAX_MESSAGE));
        }

        OffsetDateTime at = ((Resolution.Resolved) res).at();
        String wire = WIRE.format(at);
        String human = HUMAN.format(at);
        if (wantsReschedule) {
            return Decision.reschedule(target, wire,
                    cap("Moved " + reference(target) + " to " + human + ".", OutputValidator.MAX_MESSAGE));
        }
        return Decision.create(task, wire,
                cap("Reminder set: " + task + " on " + human + ".", OutputValidator.MAX_MESSAGE));
    }

    /** A clarify keeps the task only for a new reminder; a reschedule or snooze is identified by its target. */
    private static String keepTask(String task, boolean existingReminder) {
        return existingReminder ? null : task;
    }

    /** A clarify keeps the target only when an existing reminder is in play. */
    private static String keepTarget(String target, boolean existingReminder) {
        return existingReminder ? target : null;
    }

    private static String gapQuestion(List<MissingField> gaps, String task, String target, boolean snooze) {
        boolean needTask = gaps.contains(MissingField.TASK);
        boolean needTime = gaps.contains(MissingField.TIME);
        boolean needTarget = gaps.contains(MissingField.TARGET);

        if (needTarget && needTime) {
            return question(snooze
                    ? "Which reminder should I snooze, and for how long"
                    : "Which reminder should I move, and to when");
        }
        if (needTarget) {
            return question(snooze ? "Which reminder should I snooze" : "Which reminder should I move");
        }
        if (needTask && needTime) {
            return question("What should I remind you about, and when");
        }
        if (needTask) {
            return question("What should I remind you about");
        }
        if (snooze) {
            return question("How long should I snooze " + reference(target) + " for");
        }
        if (task != null) {
            return question("When should I remind you to " + task);
        }
        if (target != null) {
            return question("When should I move " + reference(target) + " to");
        }
        return question("When should I remind you");
    }

    private static String question(String text) {
        String trimmed = text.strip();
        String withMark = trimmed.endsWith("?") ? trimmed : trimmed + "?";
        if (withMark.length() <= OutputValidator.MAX_MESSAGE) {
            return withMark;
        }
        return withMark.substring(0, OutputValidator.MAX_MESSAGE - 1) + "?";
    }

    private static String reference(String target) {
        return target == null || target.isBlank() ? "that reminder" : target;
    }

    private static String cap(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max).strip();
    }
}
