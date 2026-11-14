package com.atria.todo.service;

import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.Priority;
import com.atria.todo.domain.Status;
import com.atria.todo.domain.Task;
import com.atria.todo.domain.TaskValidator;
import com.atria.todo.domain.TodoException;
import com.atria.todo.domain.Transitions;
import com.atria.todo.storage.JsonTaskRepository;
import com.atria.todo.storage.TaskStore;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The orchestration seam over {@link JsonTaskRepository}: load, apply, save.
 *
 * <p>Every command method loads the whole store, applies one change and writes it back. The store is
 * only ever saved after a successful load, so a command that exits 5 performs no write and a damaged
 * file cannot be overwritten (Constitution V).</p>
 */
public final class TaskService {

    private final JsonTaskRepository repository;
    private final Path dataPath;

    /**
     * Creates a service over one data file.
     *
     * @param repository the store reader and writer
     * @param dataPath   the validated path from {@code --data}
     */
    public TaskService(JsonTaskRepository repository, Path dataPath) {
        this.repository = repository;
        this.dataPath = dataPath;
    }

    /**
     * Creates a task: validate, default, allocate an id, persist.
     *
     * <p>Validation runs before the store is opened, so a malformed argument is reported even when the
     * data file is damaged (FR-006).</p>
     *
     * @param titleRaw the {@code --title} value
     * @param priority the parsed {@code --priority}, or {@code null} to default to {@code MEDIUM}
     * @param tagsRaw  the {@code --tags} value, or {@code null} for no tags
     * @return the created task
     */
    public Task add(String titleRaw, Priority priority, String tagsRaw) {
        String title = TaskValidator.validateTitle(titleRaw);
        List<String> tags = TaskValidator.normaliseTags(tagsRaw);
        Priority importance = priority == null ? Priority.MEDIUM : priority;
        TaskStore store = load();
        Task created = new Task(store.nextId(), title, importance, Status.TODO, tags,
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
        store.add(created);
        save(store);
        return created;
    }

    /**
     * Reads one task.
     *
     * @param id the id
     * @return the task
     * @throws TodoException {@code NOT_FOUND} when no task carries that id
     */
    public Task get(int id) {
        return require(load(), id);
    }

    /**
     * Every task, in id-ascending order.
     *
     * @return the tasks
     */
    public List<Task> listAll() {
        return list(null, null, false);
    }

    /**
     * Filters and orders the tasks. Filters combine with AND and sorting is applied after filtering
     * (FR-018..FR-021).
     *
     * @param tagQuery        the {@code --tag} value, or {@code null} for no tag filter; matched
     *                        case-insensitively because it is normalised exactly as stored tags are
     * @param status          the parsed {@code --status}, or {@code null} for no status filter
     * @param sortByPriority  {@code true} to order {@code HIGH}, {@code MEDIUM}, {@code LOW} with ties
     *                        broken by id ascending, instead of by id alone
     * @return the matching tasks, possibly empty, which is a success rather than a {@code NOT_FOUND}
     */
    public List<Task> list(String tagQuery, Status status, boolean sortByPriority) {
        List<String> wanted = TaskValidator.normaliseTags(tagQuery);
        List<Task> matching = new ArrayList<>();
        for (Task task : load().tasks()) {
            if (task.tags().containsAll(wanted) && (status == null || task.status() == status)) {
                matching.add(task);
            }
        }
        if (sortByPriority) {
            matching.sort(Comparator.comparingInt((Task task) -> task.priority().rank())
                    .thenComparingInt(Task::id));
        }
        return List.copyOf(matching);
    }

    /**
     * Changes one task's status, leaving the stored task untouched when the move is illegal.
     *
     * @param id     the id
     * @param target the requested status, already parsed from a known word
     * @return the updated task
     * @throws TodoException {@code NOT_FOUND} when no task carries that id, or
     *                       {@code INVALID_TRANSITION} when the move is not one of the four legal ones
     */
    public Task move(int id, Status target) {
        TaskStore store = load();
        Task task = require(store, id);
        if (!Transitions.allows(task.status(), target)) {
            throw new TodoException(ErrorCode.INVALID_TRANSITION,
                    "cannot move a task from " + task.status() + " to " + target);
        }
        Task moved = task.withStatus(target);
        store.replace(moved);
        save(store);
        return moved;
    }

    /**
     * Changes only the fields that were supplied. {@code id}, {@code status} and {@code createdAt} are
     * untouchable here: status changes go through {@link #move(int, Status)} only (FR-024).
     *
     * <p>Every supplied value is validated before the store is opened, so a malformed field is
     * reported even against a damaged data file (FR-006).</p>
     *
     * @param id       the id
     * @param titleRaw the {@code --title} value, or {@code null} to leave the title alone
     * @param priority the parsed {@code --priority}, or {@code null} to leave the priority alone
     * @param tagsRaw  the {@code --tags} value, or {@code null} to leave the tags alone
     * @return the updated task
     * @throws TodoException {@code NOT_FOUND} when no task carries that id
     */
    public Task update(int id, String titleRaw, Priority priority, String tagsRaw) {
        String title = titleRaw == null ? null : TaskValidator.validateTitle(titleRaw);
        List<String> tags = tagsRaw == null ? null : TaskValidator.normaliseTags(tagsRaw);
        TaskStore store = load();
        Task updated = require(store, id);
        if (title != null) {
            updated = updated.withTitle(title);
        }
        if (priority != null) {
            updated = updated.withPriority(priority);
        }
        if (tags != null) {
            updated = updated.withTags(tags);
        }
        store.replace(updated);
        save(store);
        return updated;
    }

    /**
     * Removes a task, leaving the id counter unchanged so its id is never reissued.
     *
     * @param id the id
     * @throws TodoException {@code NOT_FOUND} when no task carries that id
     */
    public void delete(int id) {
        TaskStore store = load();
        require(store, id);
        store.remove(id);
        save(store);
    }

    private TaskStore load() {
        return repository.load(dataPath);
    }

    private void save(TaskStore store) {
        repository.save(dataPath, store);
    }

    private static Task require(TaskStore store, int id) {
        Task task = store.find(id);
        if (task == null) {
            throw new TodoException(ErrorCode.NOT_FOUND, "no task with id: " + id);
        }
        return task;
    }
}
