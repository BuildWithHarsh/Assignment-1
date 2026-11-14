package com.atria.todo.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.Priority;
import com.atria.todo.domain.Status;
import com.atria.todo.domain.Task;
import com.atria.todo.domain.TodoException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Load, save, atomicity and the absent-file rule (FR-030, FR-032). */
class JsonTaskRepositoryTest {

    @TempDir
    private Path directory;

    private final JsonTaskRepository repository = new JsonTaskRepository();

    private Task sample() {
        return new Task(1, "Buy milk", Priority.MEDIUM, Status.TODO, List.of("home", "work"),
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("a missing file loads as an empty store and is not created on disk")
    void missingFileIsEmptyStoreAndNotCreated() {
        Path path = directory.resolve("absent.json");
        TaskStore store = repository.load(path);
        assertEquals(List.of(), store.tasks());
        assertEquals(0, store.lastIssuedId());
        assertFalse(Files.exists(path), "a read must not create the data file");
    }

    @Test
    @DisplayName("a save-then-load round trip preserves all six fields")
    void roundTripPreservesEveryField() {
        Path path = directory.resolve("tasks.json");
        Task original = sample();
        TaskStore store = new TaskStore(List.of(original), 7);
        repository.save(path, store);

        TaskStore reloaded = repository.load(path);
        assertEquals(7, reloaded.lastIssuedId());
        assertEquals(1, reloaded.tasks().size());
        Task read = reloaded.tasks().get(0);
        assertEquals(original.id(), read.id());
        assertEquals(original.title(), read.title());
        assertEquals(original.priority(), read.priority());
        assertEquals(original.status(), read.status());
        assertEquals(original.tags(), read.tags());
        assertEquals(original.createdAt(), read.createdAt());
    }

    @Test
    @DisplayName("no temporary file is left behind after a save")
    void noTemporaryFileLeftBehind() throws IOException {
        Path path = directory.resolve("tasks.json");
        repository.save(path, new TaskStore(List.of(sample()), 1));
        repository.save(path, new TaskStore(List.of(sample()), 2));
        try (Stream<Path> entries = Files.list(directory)) {
            List<String> leftovers = entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp")).toList();
            assertEquals(List.of(), leftovers, "atomic write must leave no .tmp file");
        }
    }

    @Test
    @DisplayName("a directory in place of the data file is a STORAGE_ERROR")
    void directoryIsStorageError() throws IOException {
        Path path = directory.resolve("adirectory");
        Files.createDirectory(path);
        TodoException thrown = assertThrows(TodoException.class, () -> repository.load(path));
        assertEquals(ErrorCode.STORAGE_ERROR, thrown.code());
    }

    @Test
    @DisplayName("a missing parent directory is a STORAGE_ERROR and writes nothing")
    void missingParentDirectoryIsStorageError() {
        Path path = directory.resolve("nope").resolve("tasks.json");
        TodoException thrown = assertThrows(TodoException.class,
                () -> repository.save(path, new TaskStore()));
        assertEquals(ErrorCode.STORAGE_ERROR, thrown.code());
        assertFalse(Files.exists(path));
    }

    @Test
    @DisplayName("a write that cannot complete is a STORAGE_ERROR and leaves no temporary file")
    void failedWriteLeavesNoTrace() throws IOException {
        Path target = directory.resolve("occupied");
        Files.createDirectory(target);
        Files.write(target.resolve("inside.txt"), new byte[] {1});
        TodoException thrown = assertThrows(TodoException.class,
                () -> repository.save(target, new TaskStore(List.of(sample()), 1)));
        assertEquals(ErrorCode.STORAGE_ERROR, thrown.code());
        try (Stream<Path> entries = Files.list(directory)) {
            assertEquals(List.of(), entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp")).toList(),
                    "a failed write must clean up its temporary file");
        }
        assertTrue(Files.isDirectory(target), "the target must be left exactly as it was");
    }

    @Test
    @DisplayName("a store rejected by the codec is reported as a STORAGE_ERROR by the repository")
    void malformedContentIsStorageError() throws IOException {
        Path path = directory.resolve("bad.json");
        Files.write(path, "{ this is not json".getBytes(StandardCharsets.UTF_8));
        assertEquals(ErrorCode.STORAGE_ERROR,
                assertThrows(TodoException.class, () -> repository.load(path)).code());
    }

    @Test
    @DisplayName("the store file is written as UTF-8 with the six fields in a fixed order")
    void storeFileShapeIsStable() throws IOException {
        Path path = directory.resolve("tasks.json");
        repository.save(path, new TaskStore(List.of(sample()), 1));
        String written = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(written.startsWith("{\"lastIssuedId\":1,\"tasks\":[{\"id\":1,\"title\":\"Buy milk\","
                + "\"priority\":\"MEDIUM\",\"status\":\"TODO\",\"tags\":[\"home\",\"work\"],\"createdAt\":\""),
                "unexpected store text: " + written);
        assertTrue(written.endsWith("Z\"}]}"), "createdAt must be an ISO-8601 UTC string: " + written);
    }

    @Test
    @DisplayName("saving the same store twice produces an identical file")
    void repeatedSavesAreByteIdentical() throws IOException {
        Path first = directory.resolve("one.json");
        Path second = directory.resolve("two.json");
        TaskStore store = new TaskStore(List.of(sample()), 3);
        repository.save(first, store);
        repository.save(second, store);
        assertEquals(new String(Files.readAllBytes(first), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(second), StandardCharsets.UTF_8));
    }
}
