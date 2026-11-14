package com.atria.reminder.cli;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * The parsed invocation (spec.md IR-01..IR-03). {@code --now} is the reference current time and the system clock is
 * never used as a fallback for it.
 */
public final class Args {

    public static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "qwen2.5:7b";
    public static final double DEFAULT_TEMPERATURE = 0.3;

    private final OffsetDateTime now;
    private final String input;
    private final String ollamaUrl;
    private final String model;
    private final double temperature;

    private Args(OffsetDateTime now, String input, String ollamaUrl, String model, double temperature) {
        this.now = now;
        this.input = input;
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.temperature = temperature;
    }

    /** Thrown for anything that must exit 2. */
    public static class InvalidArgsException extends Exception {
        public InvalidArgsException(String message) {
            super(message);
        }
    }

    public static Args parse(String[] argv, Map<String, String> env) throws InvalidArgsException {
        String nowRaw = null;
        String input = null;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--now" -> {
                    nowRaw = valueOf(argv, i, "--now");
                    i++;
                }
                case "--input" -> {
                    input = valueOf(argv, i, "--input");
                    i++;
                }
                default -> throw new InvalidArgsException("unknown argument '" + argv[i] + "'");
            }
        }
        if (nowRaw == null) {
            throw new InvalidArgsException("--now is required (ISO-8601 datetime with an explicit offset)");
        }
        if (input == null) {
            throw new InvalidArgsException("--input is required");
        }

        OffsetDateTime now;
        try {
            now = OffsetDateTime.parse(nowRaw);
        } catch (DateTimeParseException e) {
            throw new InvalidArgsException("--now '" + nowRaw + "' is not an ISO-8601 datetime with an offset");
        }

        String url = orDefault(env.get("OLLAMA_URL"), DEFAULT_OLLAMA_URL);
        String model = orDefault(env.get("REMINDER_MODEL"), DEFAULT_MODEL);
        double temperature = DEFAULT_TEMPERATURE;
        String tempRaw = env.get("REMINDER_TEMPERATURE");
        if (tempRaw != null && !tempRaw.isBlank()) {
            try {
                temperature = Double.parseDouble(tempRaw.trim());
            } catch (NumberFormatException e) {
                throw new InvalidArgsException("REMINDER_TEMPERATURE '" + tempRaw + "' is not a number");
            }
        }
        return new Args(now, input, url, model, temperature);
    }

    private static String valueOf(String[] argv, int i, String name) throws InvalidArgsException {
        if (i + 1 >= argv.length) {
            throw new InvalidArgsException(name + " requires a value");
        }
        return argv[i + 1];
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public OffsetDateTime now() {
        return now;
    }

    /** The offset every emitted {@code due_at} must carry (INV-07). */
    public ZoneOffset offset() {
        return now.getOffset();
    }

    public String input() {
        return input;
    }

    public String ollamaUrl() {
        return ollamaUrl;
    }

    public String model() {
        return model;
    }

    public double temperature() {
        return temperature;
    }
}
