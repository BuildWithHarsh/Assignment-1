package com.atria.reminder.decision;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalises the task and target text the model returns.
 *
 * <p>The prompt asks for the user's own words with lead-in filler removed, but a prompt is not an enforcement
 * mechanism (constitution IV). A model that answers "remind me" for "remind me tomorrow at 10am" has reported
 * filler, not a task, and FR-08 must still ask what the reminder is about.
 */
public final class TaskText {

    /** Lead-in filler that names the act of reminding rather than the thing to be reminded about. */
    private static final Pattern LEAD_IN = Pattern.compile(
            "^(?:please\\s+)?(?:can\\s+you\\s+|could\\s+you\\s+)?"
                    // "remind me ...", "set a reminder ...", or a bare "remind"/"reminder" with nothing after it.
                    // "remind" without "me" is left alone, so "remind the team about payroll" keeps its wording.
                    + "(?:set\\s+(?:a\\s+|an\\s+)?reminder|remind\\s+me|reminder\\b|remind(?=\\s*$))"
                    + "[\\s,:;.-]*(?:to|that|about|for|of|on)?\\s*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[\\s\"'`.,:;!?-]+|[\\s\"'`,:;-]+$");

    /** Text that refers to something without identifying it, so FR-08 and FR-09 still have to ask. */
    private static final Set<String> VAGUE = Set.of(
            "it", "this", "that", "them", "they", "one", "some", "something", "anything", "everything",
            "stuff", "things", "the thing", "that thing", "this thing", "the things", "the stuff",
            "that stuff", "this stuff", "that one", "this one", "the other one", "the usual", "the same",
            "reminder", "a reminder", "the reminder", "my reminder", "remind me", "me");

    private TaskText() {
    }

    /** @return the text with lead-in filler and edge punctuation removed, or null when nothing is left */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = EDGE_PUNCTUATION.matcher(raw).replaceAll("");
        String stripped = LEAD_IN.matcher(s).replaceFirst("");
        stripped = EDGE_PUNCTUATION.matcher(stripped).replaceAll("");
        // Only accept the stripped form if something survived; "remind me" itself leaves nothing.
        String result = stripped.isBlank() ? "" : stripped;
        return result.isBlank() ? null : result;
    }

    /** True when the text identifies nothing, whether because it is absent or merely a pronoun. */
    public static boolean isVague(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return true;
        }
        return VAGUE.contains(cleaned.strip().toLowerCase(Locale.ROOT));
    }
}
