package com.atria.reminder.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * The single object written to stdout. All eight fields are always serialised, in this order, with no others
 * (INV-01). Nullable fields are null — never an empty string and never omitted.
 */
@JsonPropertyOrder({"intent", "task", "target", "due_at", "snooze_minutes", "missing_fields", "reason_code", "message"})
public record Decision(
        @JsonProperty("intent") Intent intent,
        @JsonProperty("task") String task,
        @JsonProperty("target") String target,
        @JsonProperty("due_at") String dueAt,
        @JsonProperty("snooze_minutes") Integer snoozeMinutes,
        @JsonProperty("missing_fields") List<MissingField> missingFields,
        @JsonProperty("reason_code") ReasonCode reasonCode,
        @JsonProperty("message") String message) {

    public static Decision create(String task, String dueAt, String message) {
        return new Decision(Intent.CREATE, task, null, dueAt, null, List.of(), null, message);
    }

    public static Decision reschedule(String target, String dueAt, String message) {
        return new Decision(Intent.RESCHEDULE, null, target, dueAt, null, List.of(), null, message);
    }

    public static Decision snooze(String target, int minutes, String message) {
        return new Decision(Intent.SNOOZE, null, target, null, minutes, List.of(), null, message);
    }

    /**
     * A question. {@code due_at} and {@code snooze_minutes} are always null, even when a time or duration was
     * understood (INV-05); an understood task or target is retained unless it is itself one of the gaps.
     */
    public static Decision clarify(String task, String target, List<MissingField> missing, ReasonCode reason,
                                   String message) {
        return new Decision(Intent.CLARIFY, task, target, null, null, List.copyOf(missing), reason, message);
    }

    public static Decision reject(ReasonCode reason, String message) {
        return new Decision(Intent.REJECT, null, null, null, null, List.of(), reason, message);
    }
}
