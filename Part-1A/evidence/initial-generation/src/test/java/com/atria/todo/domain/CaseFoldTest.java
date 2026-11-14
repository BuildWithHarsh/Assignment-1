package com.atria.todo.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The ASCII keyword fold used instead of locale-sensitive library case mapping. */
class CaseFoldTest {

    @Test
    @DisplayName("ASCII letters are upper-cased")
    void asciiLettersAreFolded() {
        assertEquals("HIGH", CaseFold.upperAscii("high"));
        assertEquals("IN_PROGRESS", CaseFold.upperAscii("in_progress"));
        assertEquals("TODO", CaseFold.upperAscii("ToDo"));
        assertEquals("ABC123_-", CaseFold.upperAscii("abc123_-"));
    }

    @Test
    @DisplayName("an already-folded value is unchanged")
    void foldingIsIdempotent() {
        assertEquals("DONE", CaseFold.upperAscii("DONE"));
        assertEquals(CaseFold.upperAscii("done"), CaseFold.upperAscii(CaseFold.upperAscii("done")));
    }

    @Test
    @DisplayName("non-ASCII characters are left exactly as they are")
    void nonAsciiIsUntouched() {
        assertEquals("CAFé", CaseFold.upperAscii("café"));
        assertEquals("ß", CaseFold.upperAscii("ß"), "case mapping must not change the length");
        assertEquals(1, CaseFold.upperAscii("ı").length(), "no locale-specific dotted-i mapping");
    }

    @Test
    @DisplayName("null and empty fold to an empty string")
    void nullAndEmpty() {
        assertEquals("", CaseFold.upperAscii(null));
        assertEquals("", CaseFold.upperAscii(""));
    }
}
