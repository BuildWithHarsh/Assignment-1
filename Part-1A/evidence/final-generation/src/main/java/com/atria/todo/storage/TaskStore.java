package com.atria.todo.storage;

import com.atria.todo.domain.Task;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole contents of one data file, in memory: the tasks in id-ascending order plus the id counter.
 *
 * <p>The counter records the highest id ever issued. It is never recomputed from the tasks present and
 * {@link #remove(int)} leaves it alone, which is what makes a deleted id unreachable forever rather than
 * merely absent (FR-008, FR-029a, AC-05).</p>
 */
public final class TaskStore {

    private final List<Task> tasks;
    private int lastIssuedId;

    /** Creates an empty store with the counter at zero. */
    public TaskStore() {
        this(List.of(), 0);
    }

    /**
     * Creates a store from loaded contents.
     *
     * @param tasks        the tasks, in id-ascending order; copied, never retained
     * @param lastIssuedId the highest id ever issued in this store
     */
    public TaskStore(List<Task> tasks, int lastIssuedId) {
        this.tasks = new ArrayList<>(tasks);
        this.lastIssuedId = lastIssuedId;
    }

    /**
     * Issues the next id.
     *
     * @return the new id, one higher than the highest ever issued
     */
    public int nextId() {
        return ++lastIssuedId;
    }

    /**
     * The highest id ever issued in this store.
     *
     * @return the counter
     */
    public int lastIssuedId() {
        return lastIssuedId;
    }

    /**
     * The tasks.
     *
     * @return an immutable copy, in id-ascending order
     */
    public List<Task> tasks() {
        return List.copyOf(tasks);
    }

    /**
     * Appends a task. Ids are issued in ascending order, so appending keeps the list ordered.
     *
     * @param task the task to add
     */
    public void add(Task task) {
        tasks.add(task);
    }

    /**
     * Looks up a task.
     *
     * @param id the id to find
     * @return the task, or {@code null} when no task carries that id
     */
    public Task find(int id) {
        for (Task task : tasks) {
            if (task.id() == id) {
                return task;
            }
        }
        return null;
    }

    /**
     * Replaces a task in place, keeping its position and therefore the id ordering.
     *
     * @param task the replacement, carrying the same id
     * @return {@code true} when a task with that id was present and replaced
     */
    public boolean replace(Task task) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id() == task.id()) {
                tasks.set(i, task);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a task. The id counter is deliberately left unchanged.
     *
     * @param id the id to remove
     * @return {@code true} when a task with that id was present and removed
     */
    public boolean remove(int id) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id() == id) {
                tasks.remove(i);
                return true;
            }
        }
        return false;
    }
}
