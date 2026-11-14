package com.atria.todo.cli;

import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.Priority;
import com.atria.todo.domain.Status;
import com.atria.todo.domain.Task;
import com.atria.todo.domain.TodoException;
import com.atria.todo.service.TaskService;
import com.atria.todo.storage.JsonTaskRepository;
import com.atria.todo.storage.TaskJson;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every command runs through here: parse, dispatch, render, and map any failure to an exit code.
 *
 * <p>{@code run} <em>returns</em> the exit code rather than ending the process itself, so every exit
 * path is an ordinary unit assertion and no logic sits behind a call that would kill the test JVM
 * (research R5). Both streams are injected, so "nothing on stderr on success, nothing on stdout on
 * failure" is assertable on both sides (FR-002, FR-003, research R6).</p>
 */
public final class CliRunner {

    private static final String UNEXPECTED = "data file could not be used";

    private final JsonWriter out;
    private final JsonWriter err;

    /**
     * Creates a runner over two streams.
     *
     * @param output where a success document goes
     * @param errors where an error document goes
     */
    public CliRunner(OutputStream output, OutputStream errors) {
        this.out = new JsonWriter(output);
        this.err = new JsonWriter(errors);
    }

    /**
     * Runs one invocation.
     *
     * @param args the raw arguments
     * @return the exit code: {@code 0} on success, otherwise the code carried by the failure
     */
    public int run(String[] args) {
        try {
            return dispatch(ArgParser.parse(args));
        } catch (TodoException e) {
            return fail(e.code(), e.getMessage());
        } catch (IOException | RuntimeException e) {
            return fail(ErrorCode.STORAGE_ERROR, UNEXPECTED);
        }
    }

    private int dispatch(ParsedCommand parsed) throws IOException {
        switch (parsed.command()) {
            case "add":
                return add(parsed);
            case "get":
                return get(parsed);
            case "list":
                return list(parsed);
            case "update":
                return update(parsed);
            case "delete":
                return delete(parsed);
            case "move":
                return move(parsed);
            default:
                throw new TodoException(ErrorCode.VALIDATION_ERROR, "unknown command: " + parsed.command());
        }
    }

    private int add(ParsedCommand parsed) {
        Priority priority = optionalPriority(parsed.option("--priority"));
        Task created = service(parsed).add(parsed.option("--title"), priority, parsed.option("--tags"));
        out.write(TaskJson.toMap(created));
        return 0;
    }

    private int get(ParsedCommand parsed) {
        int id = ArgParser.parseId(parsed.positionals().get(0));
        out.write(TaskJson.toMap(service(parsed).get(id)));
        return 0;
    }

    private int list(ParsedCommand parsed) {
        Status status = optionalStatus(parsed.option("--status"));
        boolean sortByPriority = sortByPriority(parsed.option("--sort"));
        out.write(TaskJson.toList(service(parsed).list(parsed.option("--tag"), status, sortByPriority)));
        return 0;
    }

    private static boolean sortByPriority(String raw) {
        if (raw == null) {
            return false;
        }
        if (!"priority".equals(raw)) {
            throw new TodoException(ErrorCode.VALIDATION_ERROR, "unknown sort key: " + raw);
        }
        return true;
    }

    private static Status optionalStatus(String raw) {
        return raw == null ? null : Status.parse(raw);
    }

    private int update(ParsedCommand parsed) {
        int id = ArgParser.parseId(parsed.positionals().get(0));
        String title = parsed.option("--title");
        String tags = parsed.option("--tags");
        String priorityRaw = parsed.option("--priority");
        if (title == null && tags == null && priorityRaw == null) {
            throw new TodoException(ErrorCode.VALIDATION_ERROR,
                    "update requires at least one of --title, --priority, --tags");
        }
        Task updated = service(parsed).update(id, title, optionalPriority(priorityRaw), tags);
        out.write(TaskJson.toMap(updated));
        return 0;
    }

    private int delete(ParsedCommand parsed) {
        int id = ArgParser.parseId(parsed.positionals().get(0));
        service(parsed).delete(id);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("deleted", id);
        out.write(document);
        return 0;
    }

    private int move(ParsedCommand parsed) {
        int id = ArgParser.parseId(parsed.positionals().get(0));
        Status target = Status.parse(parsed.positionals().get(1));
        out.write(TaskJson.toMap(service(parsed).move(id, target)));
        return 0;
    }

    private static Priority optionalPriority(String raw) {
        return raw == null ? null : Priority.parse(raw);
    }

    private TaskService service(ParsedCommand parsed) {
        return new TaskService(new JsonTaskRepository(), ArgParser.dataPath(parsed.option(ArgParser.DATA)));
    }

    private int fail(ErrorCode code, String message) {
        err.writeError(code, message);
        return code.exitCode();
    }
}
