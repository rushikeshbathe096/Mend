package com.mend.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M4: the evaluation code (snapshot schema, statistics, metrics) is pure: no Spring, JPA, HTTP, repository or service.
 * Allowed: java.*, javax.crypto.*, Jackson (tools.jackson.*) and com.mend.evaluation itself. The simulator subpackage
 * may also use the production policy classes it replays (taxonomy, strategy, compliance, experiment, domain entities).
 */
class EvaluationPurityTest {

    private static final String BASE = "import (static )?(java\\.|javax\\.crypto\\.|tools\\.jackson\\.|com\\.mend\\.evaluation\\.";
    private static final String SIM_EXTRA = "|com\\.mend\\.(taxonomy|strategy|compliance|experiment)\\.|com\\.mend\\.domain\\.(entity|enums)\\.";

    @Test
    @DisplayName("com.mend.evaluation imports only java.*, javax.crypto.*, Jackson, itself (and, in sim, the production policy classes)")
    void pure() throws Exception {
        List<String> offending;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/mend/evaluation"))) {
            offending = files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                boolean sim = p.toString().contains("/evaluation/sim/");
                String allowed = BASE + (sim ? SIM_EXTRA : "") + ").*";
                try {
                    return Files.readAllLines(p).stream()
                            .map(String::trim)
                            .filter(l -> l.startsWith("import ") && !l.matches(allowed))
                            .map(l -> p.getFileName() + ": " + l);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        assertEquals(List.of(), offending);
    }
}
