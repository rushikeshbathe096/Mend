package com.mend.experiment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** M3 U2.1: the experiment is pure Java (no Spring, JPA, HTTP, Jackson, Razorpay adapter or other MEND package imports). */
class ExperimentPurityTest {

    @Test
    @DisplayName("com.mend.experiment (incl. subpackages) imports and references only java.*, javax.crypto.* and itself")
    void onlyJavaImports() throws Exception {
        List<String> offending;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/mend/experiment"))) {
            offending = files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try {
                    return Files.readAllLines(p).stream()
                            .map(String::trim)
                            .filter(l -> !l.startsWith("//") && !l.startsWith("*") && !l.startsWith("/*"))
                            .filter(l -> (l.startsWith("import ") && !l.matches("import (static )?(java\\.|javax\\.crypto\\.|com\\.mend\\.experiment\\.).*"))
                                    || l.matches(".*\\b(org|jakarta|tools\\.jackson)\\.[a-z].*")
                                    || l.matches(".*\\bcom\\.mend\\.(?!experiment)[a-z].*"))
                            .map(l -> p.getFileName() + ": " + l);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        assertEquals(List.of(), offending);
    }
}
