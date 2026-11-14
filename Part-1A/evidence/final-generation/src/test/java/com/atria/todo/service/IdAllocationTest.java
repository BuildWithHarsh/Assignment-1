package com.atria.todo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atria.todo.domain.Priority;
import com.atria.todo.domain.Status;
import com.atria.todo.domain.Task;
import com.atria.todo.storage.JsonTaskRepository;
import com.atria.todo.storage.TaskStore;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The id counter: sequential, monotonic, never recomputed from the tasks present (AC-05, FR-029a). */
class IdAllocationTest {

    @TempDir
    private Path directory;

    private final JsonTaskRepository repository = new JsonTaskRepository();

    private static Task task(int id) {
        return new Task(id, "task " + id, Priority.MEDIUM, Status.TODO, List.of(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("ids are issued 1, 2, 3")
    void idsAreSequential() {
        TaskStore store = new TaskStore();
        assertEquals(1, store.nextId());
        assertEquals(2, store.nextId());
        assertEquals(3, store.nextId());
        assertEquals(3, store.lastIssuedId());
    }

    @Test
    @DisplayName("removing the highest id leaves the counter alone, so the next id is 4 (AC-05)")
    void deletedIdIsNeverReissued() {
        TaskStore store = new TaskStore();
        for (int i = 1; i <= 3; i++) {
            store.add(task(store.nextId()));
        }
        assertTrue(store.remove(3));
        assertEquals(3, store.lastIssuedId(), "delete must not decrement the counter");
        assertEquals(4, store.nextId(), "the id of a deleted task must never be reissued");
    }

    @Test
    @DisplayName("the counter survives a save and load cycle")
    void counterSurvivesRoundTrip() {
        Path path = directory.resolve("tasks.json");
        TaskStore store = new TaskStore(List.of(task(1), task(2)), 9);
        repository.save(path, store);
        assertEquals(9, repository.load(path).lastIssuedId());
    }

    @Test
    @DisplayName("the counter survives emptying the task list")
    void counterSurvivesAnEmptyTaskList() {
        Path path = directory.resolve("tasks.json");
        TaskStore store = new TaskStore(List.of(task(1)), 4);
        assertTrue(store.remove(1));
        repository.save(path, store);
        TaskStore reloaded = repository.load(path);
        assertEquals(List.of(), reloaded.tasks());
        assertEquals(4, reloaded.lastIssuedId());
        assertEquals(5, reloaded.nextId());
    }

    @Test
    @DisplayName("the counter is never recomputed from the tasks present")
    void counterIsNotDerivedFromTasks() {
        TaskStore store = new TaskStore(List.of(task(1), task(2)), 7);
        assertEquals(8, store.nextId(), "the next id comes from the counter, not from max(task id)");
    }
}
