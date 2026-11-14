package com.atria.todo.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.atria.todo.cli.CliFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deliberate asymmetry between an absent file and a zero-byte one (FR-030 versus FR-031a).
 *
 * <p>An <em>absent</em> file is a clean start; a file that exists but holds nothing is
 * indistinguishable from a truncated write, so the tool stops rather than overwrite a possible
 * casualty. The locked contract suite never creates a zero-byte file, so this test is the only thing
 * standing behind that decision.</p>
 */
class ZeroByteStoreTest {

    @TempDir
    private Path directory;

    @Test
    @DisplayName("a zero-byte data file is refused for a read command and left untouched")
    void zeroByteRefusedOnRead() throws IOException {
        Path path = directory.resolve("empty.json");
        Files.write(path, new byte[0]);
        new CliFixture(path).run("list").failure(5, "STORAGE_ERROR");
        assertEquals(0, Files.size(path), "the file must be left exactly as it was found");
    }

    @Test
    @DisplayName("a zero-byte data file is refused for a write command and left untouched")
    void zeroByteRefusedOnWrite() throws IOException {
        Path path = directory.resolve("empty.json");
        Files.write(path, new byte[0]);
        new CliFixture(path).run("add", "--title", "x").failure(5, "STORAGE_ERROR");
        assertEquals(0, Files.size(path), "a refused write must not create or alter content");
    }

    @Test
    @DisplayName("whitespace-only content is refused too")
    void whitespaceOnlyRefused() throws IOException {
        Path path = directory.resolve("blank.json");
        Files.writeString(path, "   \n\t ");
        new CliFixture(path).run("list").failure(5, "STORAGE_ERROR");
    }

    @Test
    @DisplayName("an absent file is a clean start, and a read does not create it")
    void absentFileIsCleanStart() {
        Path path = directory.resolve("absent.json");
        CliFixture cli = new CliFixture(path);
        assertEquals("[]", cli.run("list").success().toString());
        assertFalse(Files.exists(path), "a read must not create the data file");
        assertEquals(1, cli.run("add", "--title", "x").success().get("id").intValue(),
                "a write against an absent file creates it and starts at id 1");
    }

    @Test
    @DisplayName("the codec, not the repository, owns the empty-content fault")
    void codecOwnsEmptyContent() {
        assertEquals(com.atria.todo.domain.ErrorCode.STORAGE_ERROR,
                org.junit.jupiter.api.Assertions.assertThrows(com.atria.todo.domain.TodoException.class,
                        () -> StoreCodec.parse("")).code());
        assertEquals(com.atria.todo.domain.ErrorCode.STORAGE_ERROR,
                org.junit.jupiter.api.Assertions.assertThrows(com.atria.todo.domain.TodoException.class,
                        () -> StoreCodec.parse("   ")).code());
    }
}
