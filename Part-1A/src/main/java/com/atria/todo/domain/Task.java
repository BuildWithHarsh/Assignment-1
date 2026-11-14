package com.atria.todo.domain;

import java.time.Instant;
import java.util.List;

/**
 * One thing the user intends to do.
 *
 * <p>Immutable: every mutating operation returns a new instance, which makes "the task is unchanged
 * after a rejected move" (AC-12) structural rather than a matter of remembering to roll back. The
 * constructor copies the incoming tag list and {@link #tags()} hands back a copy, so the internal
 * list is never shared.</p>
 */
public final class Task {

    private final int id;
    private final String title;
    private final Priority priority;
    private final Status status;
    private final List<String> tags;
    private final Instant createdAt;

    /**
     * Creates a task.
     *
     * @param id        the store-assigned id
     * @param title     the trimmed, validated title
     * @param priority  the importance
     * @param status    the lifecycle position
     * @param tags      the normalised tags; copied, never retained
     * @param createdAt the creation instant, truncated to seconds
     */
    public Task(int id, String title, Priority priority, Status status, List<String> tags, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.priority = priority;
        this.status = status;
        this.tags = List.copyOf(tags);
        this.createdAt = createdAt;
    }

    /**
     * The id.
     *
     * @return the id
     */
    public int id() {
        return id;
    }

    /**
     * The title.
     *
     * @return the title
     */
    public String title() {
        return title;
    }

    /**
     * The priority.
     *
     * @return the priority
     */
    public Priority priority() {
        return priority;
    }

    /**
     * The status.
     *
     * @return the status
     */
    public Status status() {
        return status;
    }

    /**
     * The tags.
     *
     * @return an immutable copy of the tags, sorted ascending
     */
    public List<String> tags() {
        return List.copyOf(tags);
    }

    /**
     * The creation instant.
     *
     * @return the creation instant
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * A copy with a different title.
     *
     * @param newTitle the trimmed, validated title
     * @return a new task
     */
    public Task withTitle(String newTitle) {
        return new Task(id, newTitle, priority, status, tags, createdAt);
    }

    /**
     * A copy with a different priority.
     *
     * @param newPriority the priority
     * @return a new task
     */
    public Task withPriority(Priority newPriority) {
        return new Task(id, title, newPriority, status, tags, createdAt);
    }

    /**
     * A copy with a different tag set.
     *
     * @param newTags the normalised tags
     * @return a new task
     */
    public Task withTags(List<String> newTags) {
        return new Task(id, title, priority, status, newTags, createdAt);
    }

    /**
     * A copy with a different status.
     *
     * @param newStatus the status
     * @return a new task
     */
    public Task withStatus(Status newStatus) {
        return new Task(id, title, priority, newStatus, tags, createdAt);
    }
}
