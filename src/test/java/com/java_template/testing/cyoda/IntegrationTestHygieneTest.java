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
        // Resolved from the project directory (the test task sets user.dir to it), never the raw working directory.
        Path root = Path.of(System.getProperty("user.dir")).resolve("src/integrationTest/java");
        assertThat(root).as("the integration-test sources must be found, or this check proves nothing")
                .isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(sources).as("integration-test sources under " + root).isNotEmpty();
            List<Path> offenders = sources.stream().filter(p -> {
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
