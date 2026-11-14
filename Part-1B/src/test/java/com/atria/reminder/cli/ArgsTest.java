package com.atria.reminder.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArgsTest {

    private static final String NOW = "2026-11-14T10:00:00+05:30";

    @Test
    void parsesBothOptionsInEitherOrder() throws Exception {
        Args a = Args.parse(new String[]{"--now", NOW, "--input", "call mom"}, Map.of());
        Args b = Args.parse(new String[]{"--input", "call mom", "--now", NOW}, Map.of());
        assertEquals(a.now(), b.now());
        assertEquals("call mom", b.input());
    }

    @Test
    void retainsTheSuppliedOffset() throws Exception {
        Args a = Args.parse(new String[]{"--now", NOW, "--input", "x"}, Map.of());
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), a.offset());
        Args b = Args.parse(new String[]{"--now", "2026-11-14T10:00:00-08:00", "--input", "x"}, Map.of());
        assertEquals(ZoneOffset.ofHours(-8), b.offset());
    }

    @Test
    void missingNowIsAnArgumentError() {
        assertThrows(Args.InvalidArgsException.class,
                () -> Args.parse(new String[]{"--input", "call mom"}, Map.of()));
    }

    @Test
    void nowWithoutAnOffsetIsAnArgumentError() {
        assertThrows(Args.InvalidArgsException.class,
                () -> Args.parse(new String[]{"--now", "2026-11-14T10:00:00", "--input", "x"}, Map.of()));
        assertThrows(Args.InvalidArgsException.class,
                () -> Args.parse(new String[]{"--now", "not-a-date", "--input", "x"}, Map.of()));
    }

    @Test
    void missingInputIsAnArgumentError() {
        assertThrows(Args.InvalidArgsException.class, () -> Args.parse(new String[]{"--now", NOW}, Map.of()));
    }

    @Test
    void emptyInputIsAcceptedBecauseBlanknessIsADecision() throws Exception {
        Args a = Args.parse(new String[]{"--now", NOW, "--input", ""}, Map.of());
        assertEquals("", a.input());
    }

    @Test
    void unparseableTemperatureIsAnArgumentError() {
        assertThrows(Args.InvalidArgsException.class, () -> Args.parse(
                new String[]{"--now", NOW, "--input", "x"}, Map.of("REMINDER_TEMPERATURE", "warm")));
    }

    @Test
    void environmentDefaultsApplyWhenUnset() throws Exception {
        Args a = Args.parse(new String[]{"--now", NOW, "--input", "x"}, Map.of());
        assertEquals(Args.DEFAULT_OLLAMA_URL, a.ollamaUrl());
        assertEquals(Args.DEFAULT_MODEL, a.model());
        assertEquals(Args.DEFAULT_TEMPERATURE, a.temperature(), 1e-9);
    }

    @Test
    void environmentOverridesAreHonoured() throws Exception {
        Args a = Args.parse(new String[]{"--now", NOW, "--input", "x"}, Map.of(
                "OLLAMA_URL", "http://127.0.0.1:9999",
                "REMINDER_MODEL", "llama3.1:8b",
                "REMINDER_TEMPERATURE", "0.0"));
        assertEquals("http://127.0.0.1:9999", a.ollamaUrl());
        assertEquals("llama3.1:8b", a.model());
        assertEquals(0.0, a.temperature(), 1e-9);
    }

    @Test
    void theSystemClockIsNeverUsedForNow() throws Exception {
        Args a = Args.parse(new String[]{"--now", "2001-01-01T00:00:00Z", "--input", "x"}, Map.of());
        assertEquals(2001, a.now().getYear());
        assertTrue(a.now().isBefore(java.time.OffsetDateTime.now()));
    }
}
