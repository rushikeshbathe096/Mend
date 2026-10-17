package com.mend.testsupport;

import com.mend.config.DevDataInitializer;
import com.mend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug sweep item 6: test classes share one database per run, so none may wipe a shared table, and the test profile keeps
 * debug logging and the dev seed off.
 */
class TestHygieneTest extends AbstractIntegrationTest {

    /** M3-owned (AI path): their cleanup is left to M3 (docs/audit/bug-registry.md BR-06b). */
    private static final Set<String> M3_OWNED = Set.of("ClassificationServiceTest.java", "AiRecoveryAgentIntegrationTest.java");

    private static final Pattern TABLE_WIPE = Pattern.compile(
            "\\b\\w+(Repository|RepositoryU9)\\s*\\.\\s*deleteAll(InBatch)?\\s*\\(\\s*\\)|(?i)\\bTRUNCATE\\s+(TABLE\\s+)?\\w");

    @Autowired private Environment environment;
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("No test wipes a shared table (deleteAll() / deleteAllInBatch() / TRUNCATE)")
    void noSharedTableWipes() throws IOException {
        Path root = Path.of("src/test/java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !M3_OWNED.contains(p.getFileName().toString()))
                    .filter(p -> !p.getFileName().toString().equals("TestHygieneTest.java"))
                    .flatMap(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p);
                            return java.util.stream.IntStream.range(0, lines.size())
                                    .filter(i -> !lines.get(i).trim().startsWith("//") && !lines.get(i).trim().startsWith("*"))
                                    .filter(i -> TABLE_WIPE.matcher(lines.get(i)).find())
                                    .mapToObj(i -> root.relativize(p) + ":" + (i + 1) + "  " + lines.get(i).trim());
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
        }
        assertTrue(offenders.isEmpty(), "use per-test cleanup (TestRows) instead:\n" + String.join("\n", offenders));
    }

    @Test
    @DisplayName("Test profile: no debug report, no SQL logging, no dev seed")
    void testProfileIsQuiet() {
        assertFalse(environment.getProperty("debug", Boolean.class, false), "debug=true writes the auto-config report and DEBUG logs");
        assertFalse(environment.getProperty("spring.jpa.show-sql", Boolean.class, false));
        assertFalse(environment.getProperty("mend.dev.seed", Boolean.class, false));
        assertTrue(context.getBeansOfType(DevDataInitializer.class).isEmpty(), "the dev seed must not run in test contexts");
    }
}
