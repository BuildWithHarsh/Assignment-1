package com.atria.todo.cli;

/**
 * The entry point, and the only place in the program that calls {@code System.exit}.
 *
 * <p>Keeping the call here is what makes the coverage gate reachable: an exit reached from test code
 * would terminate the JUnit JVM and take the whole test run with it (research R5).</p>
 */
public final class Main {

    private Main() {
    }

    /**
     * Runs one invocation and exits with its code.
     *
     * @param args the raw command-line arguments
     */
    public static void main(String[] args) {
        CliRunner runner = new CliRunner(System.out, System.err);
        System.exit(runner.run(args));
    }
}
