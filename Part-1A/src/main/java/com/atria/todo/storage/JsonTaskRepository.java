package com.atria.todo.storage;

import com.atria.todo.domain.ErrorCode;
import com.atria.todo.domain.TodoException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and writes the one JSON file named by {@code --data}.
 *
 * <p>An <em>absent</em> file is a clean start and is not created by a read (FR-030); a file that is
 * present is handed to {@link StoreCodec} whatever its size, so a zero-byte file is damage rather than
 * an empty list (FR-031a). Writes go to a temporary file in the <em>same directory</em> and are then
 * moved atomically: a temp file on another volume cannot move atomically (FR-032, research R4).</p>
 */
public final class JsonTaskRepository {

    private static final String UNREADABLE = "data file cannot be read";
    private static final String UNWRITABLE = "data file cannot be written";

    /**
     * Loads the store.
     *
     * @param path the data file path
     * @return the stored tasks, or an empty store when the file does not exist
     * @throws TodoException {@code STORAGE_ERROR} when the path is not a readable regular file, or its
     *                       contents are not a valid store
     */
    public TaskStore load(Path path) {
        if (!Files.exists(path)) {
            return new TaskStore();
        }
        if (!Files.isRegularFile(path)) {
            throw new TodoException(ErrorCode.STORAGE_ERROR, UNREADABLE);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new TodoException(ErrorCode.STORAGE_ERROR, UNREADABLE);
        }
        return StoreCodec.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Writes the store atomically, leaving the target untouched on any failure.
     *
     * @param path  the data file path
     * @param store the store to write
     * @throws TodoException {@code STORAGE_ERROR} when the file cannot be written
     */
    public void save(Path path, TaskStore store) {
        byte[] bytes = StoreCodec.serialise(store);
        Path directory = path.toAbsolutePath().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new TodoException(ErrorCode.STORAGE_ERROR, UNWRITABLE);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, "tasks", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            discard(temporary);
            throw new TodoException(ErrorCode.STORAGE_ERROR, UNWRITABLE);
        }
    }

    private static void discard(Path temporary) {
        if (temporary == null) {
            return;
        }
        File leftover = temporary.toFile();
        if (leftover.exists() && !leftover.delete()) {
            leftover.deleteOnExit();
        }
    }
}
