package com.atria.todo.storage;

import com.atria.todo.domain.Priority;
import com.atria.todo.domain.Status;
import com.atria.todo.domain.Task;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one mapping between {@link Task} and its JSON form.
 *
 * <p>Both the store file and stdout go through this class, so the two surfaces cannot drift apart. The
 * field order is built explicitly into a {@link LinkedHashMap} rather than left to reflection, because
 * Jackson's POJO ordering is unspecified and Constitution IV requires stable output (research R2).</p>
 */
public final class TaskJson {

    /** The task field names, in the order they are always emitted. */
    public static final List<String> FIELDS = List.of("id", "title", "priority", "status", "tags", "createdAt");

    private TaskJson() {
    }

    /**
     * Renders one task.
     *
     * @param task the task
     * @return an ordered map of the six fields, with {@code createdAt} as an ISO-8601 UTC string
     */
    public static Map<String, Object> toMap(Task task) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("id", task.id());
        rendered.put("title", task.title());
        rendered.put("priority", task.priority().name());
        rendered.put("status", task.status().name());
        rendered.put("tags", task.tags());
        rendered.put("createdAt", task.createdAt().toString());
        return rendered;
    }

    /**
     * Renders a list of tasks.
     *
     * @param tasks the tasks, already filtered and ordered
     * @return one ordered map per task
     */
    public static List<Map<String, Object>> toList(List<Task> tasks) {
        List<Map<String, Object>> rendered = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            rendered.add(toMap(task));
        }
        return rendered;
    }

    /**
     * Reads one task back. The node must already have passed {@link StoreCodec}'s shape validation,
     * which owns every content fault (research R10).
     *
     * @param node a validated task object
     * @return the task
     */
    public static Task fromNode(JsonNode node) {
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : node.get("tags")) {
            tags.add(tag.textValue());
        }
        return new Task(node.get("id").intValue(),
                node.get("title").textValue(),
                Priority.valueOf(node.get("priority").textValue()),
                Status.valueOf(node.get("status").textValue()),
                tags,
                Instant.parse(node.get("createdAt").textValue()));
    }
}
