package com.atria.todo.domain;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Title and tag rules (FR-010, FR-013), applied identically on {@code add} and {@code update}. */
public final class TaskValidator {

    /** The longest title accepted, measured after trimming. */
    public static final int MAX_TITLE_LENGTH = 200;

    private TaskValidator() {
    }

    /**
     * Trims a title and checks it.
     *
     * @param raw the value as the user typed it
     * @return the trimmed title
     * @throws TodoException {@code VALIDATION_ERROR} if the title is absent, blank after trimming, or
     *                       longer than {@value #MAX_TITLE_LENGTH} characters after trimming
     */
    public static String validateTitle(String raw) {
        if (raw == null) {
            throw new TodoException(ErrorCode.VALIDATION_ERROR, "missing required option: --title");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new TodoException(ErrorCode.VALIDATION_ERROR, "title must not be blank");
        }
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new TodoException(ErrorCode.VALIDATION_ERROR,
                    "title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * Normalises a comma-separated tag value: split on {@code ,}, trim, lower-case with
     * {@code Locale.ROOT}, drop empty entries, de-duplicate, sort ascending.
     *
     * <p>A value that collapses to nothing yields an empty list and is <em>not</em> an error.</p>
     *
     * @param raw the value as the user typed it, or {@code null} when the option was omitted
     * @return the normalised tags, sorted ascending
     */
    public static List<String> normaliseTags(String raw) {
        if (raw == null) {
            return List.of();
        }
        TreeSet<String> unique = new TreeSet<>();
        for (String part : raw.split(",", -1)) {
            String tag = part.trim().toLowerCase(Locale.ROOT);
            if (!tag.isEmpty()) {
                unique.add(tag);
            }
        }
        return List.copyOf(unique);
    }
}
