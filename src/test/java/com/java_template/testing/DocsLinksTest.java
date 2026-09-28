package com.java_template.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: Scans the docs that contributors and AI agents read first for backticked or linked
 * repo-relative paths, and asserts each one exists. A path that moved (schema/ to cyoda/schema/,
 * llm_example/ to src/test/java/com/example/) or was never created (.augment-guidelines,
 * .../patterns/, prototype/*.md) misleads a reader who trusts the docs over the code.
 */
class DocsLinksTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    /** Fenced ``` blocks hold example AI-agent prompts and ASCII trees, not doc references. */
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```.*?```");

    private static final Pattern BACKTICK_SPAN = Pattern.compile("`([^`\n]+)`");
    private static final Pattern LINK_TARGET = Pattern.compile("]\\(([^)\\s]+)\\)");

    /** Only the path shapes the task calls out: src/…, scripts/…, llm_example/…, docs/…, .augment-guidelines. */
    private static final Pattern REPO_PATH = Pattern.compile(
            "(?:src|scripts|llm_example|docs)/[A-Za-z0-9_./$-]+|\\.augment-guidelines"
    );

    /**
     * Directories the template deliberately ships without: users create application code and
     * workflow JSON under these, so a fresh checkout never has them.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            "src/main/java/com/java_template/application/",
            "src/main/java/com/java_template/application/controller/",
            "src/main/java/com/java_template/application/entity/",
            "src/main/java/com/java_template/application/processor/",
            "src/main/java/com/java_template/application/criterion/",
            "src/main/resources/workflow/",
            "src/main/resources/workflow"
    );

    /** A `$entity_name`/`$version` placeholder is a naming pattern to substitute, not a real path. */
    private static boolean isPlaceholder(String path) {
        return path.contains("$");
    }

    private static List<String> extractPaths(String text) {
        String withoutFences = FENCED_BLOCK.matcher(text).replaceAll("");
        List<String> found = new ArrayList<>();
        collectFromSpans(BACKTICK_SPAN.matcher(withoutFences), found);
        collectFromSpans(LINK_TARGET.matcher(withoutFences), found);
        return found;
    }

    private static void collectFromSpans(Matcher spanMatcher, List<String> found) {
        while (spanMatcher.find()) {
            Matcher pathMatcher = REPO_PATH.matcher(spanMatcher.group(1));
            while (pathMatcher.find()) {
                found.add(pathMatcher.group());
            }
        }
    }

    private static List<Path> targetDocs() throws IOException {
        List<Path> docs = new ArrayList<>(List.of(
                ROOT.resolve("README.md"),
                ROOT.resolve("CONTRIBUTING.md"),
                ROOT.resolve("usage-rules.md"),
                ROOT.resolve("AI_TESTING_GUIDE.md"),
                ROOT.resolve("llms.txt"),
                ROOT.resolve("llms-full.txt")
        ));
        Path rulesDir = ROOT.resolve(".augment/rules");
        if (Files.isDirectory(rulesDir)) {
            try (Stream<Path> entries = Files.list(rulesDir)) {
                entries.filter(p -> p.toString().endsWith(".md")).forEach(docs::add);
            }
        }
        return docs;
    }

    @Test
    void everyReferencedRepoPathExists() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path doc : targetDocs()) {
            if (!Files.exists(doc)) {
                continue;
            }
            String text = Files.readString(doc);
            for (String path : extractPaths(text)) {
                if (isPlaceholder(path) || ALLOWLIST.contains(path)) {
                    continue;
                }
                if (!Files.exists(ROOT.resolve(path))) {
                    offenders.add(ROOT.relativize(doc) + " -> " + path);
                }
            }
        }

        assertThat(offenders).as("dead repo-relative paths referenced in docs").isEmpty();
    }
}
