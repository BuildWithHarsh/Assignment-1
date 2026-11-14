package com.atria.reminder.output;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The failure report for exits 1, 2, 6 and 7. Written to stderr only, so a failed run leaves stdout empty rather
 * than unparseable.
 *
 * <p>The message is operator-facing and must never contain the canary, a detected credential value, or the raw
 * model response verbatim.
 */
public final class ErrorEnvelope {

    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    public static final String MODEL_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID";

    private ErrorEnvelope() {
    }

    public static String toJson(String code, String message) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + escape(message) + "\"}}";
    }

    public static void write(String code, String message) {
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), false, StandardCharsets.UTF_8);
        err.print(toJson(code, message));
        err.print('\n');
        err.flush();
    }

    private static String escape(String s) {
        String safe = s == null ? "" : s;
        StringBuilder b = new StringBuilder(safe.length() + 16);
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n', '\r' -> b.append(' ');
                case '\t' -> b.append(' ');
                default -> {
                    if (c < 0x20) {
                        b.append(' ');
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
