package com.atria.reminder.model;

/**
 * The model's answer: language understanding only. Untrusted — every field is re-checked by TimeResolver,
 * DecisionMapper and OutputValidator before anything is printed (constitution IV).
 */
public record Extraction(
        Action action,
        String task,
        String target,
        boolean taskVague,
        boolean targetVague,
        TimeExpression time,
        Integer snoozeMinutes,
        Safety safety) {

    public enum Action { CREATE, RESCHEDULE, SNOOZE, OTHER, NONE }

    public enum Safety { OK, INJECTION, SECRET, UNSUPPORTED }

    public boolean hasUsableTask() {
        return task != null && !task.isBlank() && !taskVague;
    }

    public boolean hasUsableTarget() {
        return target != null && !target.isBlank() && !targetVague;
    }
}
