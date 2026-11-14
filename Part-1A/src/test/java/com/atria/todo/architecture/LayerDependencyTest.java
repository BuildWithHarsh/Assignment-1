package com.atria.todo.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Constitution VI as a test rather than a convention: dependencies point inward,
 * {@code cli -> service -> storage -> domain}, and {@code domain} depends on nothing but the JDK.
 *
 * <p>Direction is asserted, never adjacency: skipping a layer <em>inward</em> is allowed, so
 * {@code cli} may import {@code storage} directly.</p>
 */
class LayerDependencyTest {

    private static final Path MAIN = Paths.get("src", "main", "java", "com", "atria", "todo");

    private static final Map<String, List<String>> FORBIDDEN = Map.of(
            "domain", List.of("com.atria.todo.storage", "com.atria.todo.service", "com.atria.todo.cli",
                    "com.fasterxml.jackson"),
            "storage", List.of("com.atria.todo.service", "com.atria.todo.cli"),
            "service", List.of("com.atria.todo.cli"),
            "cli", List.of());

    private static List<Path> sourcesIn(String layer) throws IOException {
        try (Stream<Path> files = Files.list(MAIN.resolve(layer))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    private static List<String> importsOf(Path source) throws IOException {
        List<String> imports = new ArrayList<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                imports.add(trimmed.substring("import ".length()).replace("static ", "").replace(";", ""));
            }
        }
        return imports;
    }

    @Test
    @DisplayName("the four layers exist and hold source files")
    void layersExist() throws IOException {
        for (String layer : List.of("domain", "storage", "service", "cli")) {
            assertTrue(Files.isDirectory(MAIN.resolve(layer)), "missing layer: " + layer);
            assertFalse(sourcesIn(layer).isEmpty(), "empty layer: " + layer);
        }
    }

    @Test
    @DisplayName("no main source imports outward")
    void noOutwardImports() throws IOException {
        List<String> breaches = new ArrayList<>();
        for (Map.Entry<String, List<String>> rule : FORBIDDEN.entrySet()) {
            for (Path source : sourcesIn(rule.getKey())) {
                for (String imported : importsOf(source)) {
                    for (String banned : rule.getValue()) {
                        if (imported.startsWith(banned)) {
                            breaches.add(source.getFileName() + " imports " + imported);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), breaches, "layer dependencies must point inward");
    }

    @Test
    @DisplayName("domain depends on nothing but the JDK")
    void domainUsesOnlyTheJdk() throws IOException {
        for (Path source : sourcesIn("domain")) {
            for (String imported : importsOf(source)) {
                assertTrue(imported.startsWith("java."),
                        source.getFileName() + " imports " + imported + " outside the JDK");
            }
        }
    }

    @Test
    @DisplayName("skipping a layer inward is allowed, so cli may import storage")
    void inwardLayerSkipIsAllowed() throws IOException {
        boolean found = false;
        for (Path source : sourcesIn("cli")) {
            found = found || importsOf(source).stream().anyMatch(i -> i.startsWith("com.atria.todo.storage"));
        }
        assertTrue(found, "this test must prove an inward skip does not fail noOutwardImports");
    }

    @Test
    @DisplayName("System.exit appears only in Main")
    void systemExitOnlyInMain() throws IOException {
        for (String layer : List.of("domain", "storage", "service", "cli")) {
            for (Path source : sourcesIn(layer)) {
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                boolean isMain = source.getFileName().toString().equals("Main.java");
                assertEquals(isMain, text.contains("System.exit("),
                        "System.exit must appear in Main.java only, found in " + source.getFileName());
            }
        }
    }

    @Test
    @DisplayName("no main source reaches for System.out or System.err outside Main")
    void streamsAreInjected() throws IOException {
        for (String layer : List.of("domain", "storage", "service", "cli")) {
            for (Path source : sourcesIn(layer)) {
                if (source.getFileName().toString().equals("Main.java")) {
                    continue;
                }
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                assertFalse(text.contains("System.out") || text.contains("System.err"),
                        source.getFileName() + " must take its streams by injection");
            }
        }
    }
}
