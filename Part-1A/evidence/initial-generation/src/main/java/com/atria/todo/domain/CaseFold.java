package com.atria.todo.domain;

/**
 * ASCII-only case folding for keyword matching.
 *
 * <p>The enum constants this compares against ({@code LOW}, {@code HIGH}, {@code IN_PROGRESS} and the
 * rest) are ASCII keywords, so folding only {@code a}-{@code z} is exact for every value that can
 * match and leaves everything else untouched for the rejection path.</p>
 *
 * <p>It is used instead of {@code String.toUpperCase} or {@code equalsIgnoreCase} because library case
 * mapping is locale- and Unicode-sensitive: under a Turkish locale {@code "list".toUpperCase()} yields
 * {@code "LİST"}, and case mapping can change a string's length ({@code "ß"} becomes {@code "SS"}).
 * Both make keyword matching depend on something other than the keyword, which Constitution IV
 * forbids; the static-analysis gate flags the library calls for the same reason.</p>
 */
public final class CaseFold {

    private static final int CASE_DISTANCE = 'a' - 'A';

    private CaseFold() {
    }

    /**
     * Upper-cases the ASCII letters in a value and leaves every other character as it is.
     *
     * @param raw the value as the user typed it, or {@code null}
     * @return the folded value, or an empty string when {@code raw} is {@code null}
     */
    public static String upperAscii(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder folded = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (character >= 'a' && character <= 'z') {
                folded.append((char) (character - CASE_DISTANCE));
            } else {
                folded.append(character);
            }
        }
        return folded.toString();
    }
}
