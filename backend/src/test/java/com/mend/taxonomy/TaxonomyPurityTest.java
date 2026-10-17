package com.mend.taxonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** M3 U2.1: the taxonomy is pure Java (no Spring, JPA, HTTP, Jackson, Razorpay adapter or other MEND package imports). */
class TaxonomyPurityTest {

    @Test
    @DisplayName("com.mend.taxonomy imports only java.* and itself")
    void onlyJavaImports() throws Exception {
        List<String> offending;
        try (Stream<Path> files = Files.list(Path.of("src/main/java/com/mend/taxonomy"))) {
            offending = files.flatMap(p -> {
                try {
                    return Files.readAllLines(p).stream()
                            .filter(l -> l.startsWith("import "))
                            .filter(l -> !l.matches("import (static )?(java\\.|com\\.mend\\.taxonomy\\.).*"))
                            .map(l -> p.getFileName() + ": " + l);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        assertEquals(List.of(), offending);
    }
}
