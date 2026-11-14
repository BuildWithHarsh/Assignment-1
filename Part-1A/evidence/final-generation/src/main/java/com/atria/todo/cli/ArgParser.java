package com.atria.todo.cli;

import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.TodoException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns raw arguments into a {@link ParsedCommand}, rejecting rather than guessing.
 *
 * <p>Refused with {@code VALIDATION_ERROR}: a missing {@code --data}, an unknown command, an unknown
 * option name, a repeated option, an option without a value, and a wrong number of positional
 * arguments (FR-001, FR-005, FR-005a). Option names match case-sensitively; option <em>values</em> for
 * enums are parsed case-insensitively by the enums themselves.</p>
 */
public final class ArgParser {

    /** The global option that names the data file. */
    public static final String DATA = "--data";

    private static final Map<String, Set<String>> COMMAND_OPTIONS = Map.of(
            "add", Set.of("--title", "--priority", "--tags"),
            "get", Set.of(),
            "list", Set.of("--tag", "--status", "--sort"),
            "update", Set.of("--title", "--priority", "--tags"),
            "delete", Set.of(),
            "move", Set.of());

    private static final Map<String, Integer> COMMAND_ARITY = Map.of(
            "add", 0, "get", 1, "list", 0, "update", 1, "delete", 1, "move", 2);

    private ArgParser() {
    }

    /**
     * Parses one invocation.
     *
     * @param args the raw arguments, exactly as the JVM received them
     * @return the parsed invocation
     * @throws TodoException {@code VALIDATION_ERROR} for any argument fault
     */
    public static ParsedCommand parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        int index = readGlobalOptions(args, options);
        if (!options.containsKey(DATA)) {
            throw validation("missing required option: " + DATA);
        }
        if (index >= args.length) {
            throw validation("missing command");
        }
        String command = args[index];
        if (!COMMAND_OPTIONS.containsKey(command)) {
            throw validation("unknown command: " + command);
        }
        List<String> positionals = new ArrayList<>();
        readCommandTokens(args, index + 1, command, options, positionals);
        int expected = COMMAND_ARITY.get(command);
        if (positionals.size() != expected) {
            throw validation(command + " expects " + expected + " positional argument(s), got "
                    + positionals.size());
        }
        return new ParsedCommand(command, options, positionals);
    }

    private static int readGlobalOptions(String[] args, Map<String, String> options) {
        int index = 0;
        while (index < args.length && args[index].startsWith("--")) {
            String name = args[index];
            if (!DATA.equals(name)) {
                throw validation("unknown option: " + name);
            }
            put(options, name, valueAt(args, index));
            index += 2;
        }
        return index;
    }

    private static void readCommandTokens(String[] args, int from, String command,
                                          Map<String, String> options, List<String> positionals) {
        Set<String> allowed = COMMAND_OPTIONS.get(command);
        int index = from;
        while (index < args.length) {
            String token = args[index];
            if (token.startsWith("--")) {
                if (!allowed.contains(token)) {
                    throw validation("unknown option: " + token);
                }
                put(options, token, valueAt(args, index));
                index += 2;
            } else {
                positionals.add(token);
                index++;
            }
        }
    }

    private static void put(Map<String, String> options, String name, String value) {
        if (options.containsKey(name)) {
            throw validation("repeated option: " + name);
        }
        options.put(name, value);
    }

    private static String valueAt(String[] args, int index) {
        if (index + 1 >= args.length) {
            throw validation("missing value for option: " + args[index]);
        }
        return args[index + 1];
    }

    /**
     * Parses a positional id.
     *
     * @param raw the value as the user typed it
     * @return the id
     * @throws TodoException {@code VALIDATION_ERROR} when the value is not an integer, raised before any
     *                       lookup is attempted (FR-014)
     */
    public static int parseId(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw validation("id must be an integer: " + raw);
        }
    }

    /**
     * Validates the {@code --data} value and turns it into a path.
     *
     * <p>Every character is checked against an allow-list and the path is rebuilt from the accepted
     * characters, so a control character cannot reach the filesystem API. This is the one place in the
     * program where a user-supplied string becomes a {@link Path}; see research R3 and the probe
     * results recorded with it.</p>
     *
     * @param raw the {@code --data} value
     * @return the normalised path
     * @throws TodoException {@code STORAGE_ERROR} when the value cannot name a usable file
     */
    public static Path dataPath(String raw) {
        StringBuilder accepted = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (character < ' ' || character == '\u007f') {
                throw new TodoException(ErrorCode.STORAGE_ERROR, "data file path is not usable");
            }
            accepted.append(character);
        }
        try {
            return Paths.get(accepted.toString()).normalize();
        } catch (InvalidPathException e) {
            throw new TodoException(ErrorCode.STORAGE_ERROR, "data file path is not usable");
        }
    }

    private static TodoException validation(String message) {
        return new TodoException(ErrorCode.VALIDATION_ERROR, message);
    }
}
