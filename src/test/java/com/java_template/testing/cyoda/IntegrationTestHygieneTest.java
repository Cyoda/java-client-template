package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** A forked Spring context would stay joined with the same tag and steal callouts (spec §6.1). */
class IntegrationTestHygieneTest {

    @Test
    void integrationTestsDeclareNoMockBeans() throws Exception {
        Path root = Path.of("src/integrationTest/java");
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String src = Files.readString(p);
                    return src.contains("@MockitoBean") || src.contains("@MockBean");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(offenders).as("integration tests must not fork the Spring context with mock beans").isEmpty();
        }
    }
}
