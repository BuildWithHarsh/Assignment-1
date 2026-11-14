package com.atria.todo.cli;

import java.util.List;
import java.util.Map;

/** One validated invocation: the command name, its options and its positional arguments. */
public final class ParsedCommand {

    private final String command;
    private final Map<String, String> options;
    private final List<String> positionals;

    /**
     * Creates a parsed invocation.
     *
     * @param command     the command name
     * @param options     the options, keyed by their {@code --name} spelling; copied, never retained
     * @param positionals the positional arguments, in order; copied, never retained
     */
    public ParsedCommand(String command, Map<String, String> options, List<String> positionals) {
        this.command = command;
        this.options = Map.copyOf(options);
        this.positionals = List.copyOf(positionals);
    }

    /**
     * The command name.
     *
     * @return the command name
     */
    public String command() {
        return command;
    }

    /**
     * One option's value.
     *
     * @param name the option's {@code --name} spelling
     * @return the value, or {@code null} when the option was not given
     */
    public String option(String name) {
        return options.get(name);
    }

    /**
     * All options.
     *
     * @return an immutable copy
     */
    public Map<String, String> options() {
        return Map.copyOf(options);
    }

    /**
     * The positional arguments.
     *
     * @return an immutable copy, in order
     */
    public List<String> positionals() {
        return List.copyOf(positionals);
    }
}
