package com.java_template.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: Scans the docs that contributors and AI agents read first for repo-relative paths —
 * backticked or linked outside a fenced code block, and bare inside one, since a shell command or
 * file listing doesn't wrap its own paths in backticks — and asserts each one exists. A path that
 * moved (schema/ to cyoda/schema/, llm_example/ to src/test/java/com/example/) or was never
 * created (.augment-guidelines, .../patterns/, prototype/*.md) misleads a reader who trusts the
 * docs over the code.
 */
class DocsLinksTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```.*?```");
    private static final Pattern BACKTICK_SPAN = Pattern.compile("`([^`\n]+)`");
    private static final Pattern LINK_TARGET = Pattern.compile("]\\(([^)\\s]+)\\)");

    /**
     * Only the path shapes the task calls out: src/…, scripts/…, llm_example/…, docs/…,
     * .augment-guidelines. The leading {@code (?<![\w./-])} is a left boundary: without it, the
     * pattern would match the "src/…" tail inside "buildSrc/src/…" or a URL like
     * ".../other-repo/blob/main/lib/src/utils.js", checking a path that was never actually
     * referenced against this repo's root.
     */
    private static final Pattern REPO_PATH = Pattern.compile(
            "(?<![\\w./-])(?:src|scripts|llm_example|docs)/[A-Za-z0-9_./$-]+|\\.augment-guidelines"
    );

    /**
     * Directories the template deliberately ships without: users create application code and
     * workflow/entity JSON under these, so a fresh checkout never has them.
     */
    private static final List<String> ALLOWLIST_PREFIXES = List.of(
            "src/main/java/com/java_template/application/",
            "src/main/resources/workflow/",
            "src/main/resources/entity/",
            "src/main/resources/entity-schemas/"
    );

    /** A `$entity_name` / `<placeholder>` / `{value}` token names a pattern to substitute, not a real path. */
    private static boolean isPlaceholder(String path) {
        return path.contains("$") || path.contains("<") || path.contains("{");
    }

    private static boolean isAllowlisted(String path) {
        return ALLOWLIST_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * Backticked spans and link targets outside fenced blocks (prose referencing a path); bare
     * repo-path-shaped tokens inside fenced blocks (example shell commands and file listings).
     */
    private static List<String> extractPaths(String text) {
        List<String> found = new ArrayList<>();
        Matcher fence = FENCED_BLOCK.matcher(text);
        int last = 0;
        StringBuilder outsideFences = new StringBuilder();
        while (fence.find()) {
            outsideFences.append(text, last, fence.start());
            last = fence.end();
            collectBarePaths(fence.group(), found);
        }
        outsideFences.append(text, last, text.length());
        collectFromSpans(BACKTICK_SPAN.matcher(outsideFences), found);
        collectFromSpans(LINK_TARGET.matcher(outsideFences), found);
        return found;
    }

    private static void collectBarePaths(CharSequence block, List<String> found) {
        Matcher pathMatcher = REPO_PATH.matcher(block);
        while (pathMatcher.find()) {
            found.add(pathMatcher.group());
        }
    }

    private static void collectFromSpans(Matcher spanMatcher, List<String> found) {
        while (spanMatcher.find()) {
            collectBarePaths(spanMatcher.group(1), found);
        }
    }

    /** Docs that must exist and be scanned; a missing one fails loudly rather than being skipped. */
    private static List<Path> requiredDocs() {
        return List.of(
                ROOT.resolve("README.md"),
                ROOT.resolve("CONTRIBUTING.md"),
                ROOT.resolve("usage-rules.md"),
                ROOT.resolve("AI_TESTING_GUIDE.md"),
                ROOT.resolve("llms.txt"),
                ROOT.resolve("llms-full.txt"),
                ROOT.resolve("SYNCING_WITH_JAVA_TEMPLATE.md")
        );
    }

    /** `.augment/rules/*.md` is genuinely optional: not every checkout has an Augment setup. */
    private static List<Path> optionalDocs() throws IOException {
        List<Path> docs = new ArrayList<>();
        Path rulesDir = ROOT.resolve(".augment/rules");
        if (Files.isDirectory(rulesDir)) {
            try (Stream<Path> entries = Files.list(rulesDir)) {
                entries.filter(p -> p.toString().endsWith(".md")).forEach(docs::add);
            }
        }
        return docs;
    }

    /** Every dead-path offender found scanning {@code docs}, resolving each extracted path against {@code root}. */
    private static List<String> offendersIn(Path root, List<Path> docs) throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path doc : docs) {
            String text = Files.readString(doc);
            for (String path : extractPaths(text)) {
                if (isPlaceholder(path) || isAllowlisted(path)) {
                    continue;
                }
                if (!Files.exists(root.resolve(path))) {
                    offenders.add(doc + " -> " + path);
                }
            }
        }
        return offenders;
    }

    @Test
    void everyReferencedRepoPathExists() throws IOException {
        for (Path doc : requiredDocs()) {
            assertThat(Files.exists(doc)).as("required doc exists: %s", ROOT.relativize(doc)).isTrue();
        }
        List<Path> docs = new ArrayList<>(requiredDocs());
        docs.addAll(optionalDocs());

        int pathsChecked = 0;
        for (Path doc : docs) {
            pathsChecked += extractPaths(Files.readString(doc)).size();
        }
        assertThat(pathsChecked).as("at least one repo-relative path was checked").isPositive();

        assertThat(offendersIn(ROOT, docs)).as("dead repo-relative paths referenced in docs").isEmpty();
    }

    @Test
    void theScannerCatchesADeadPathInjectedIntoADoc(@TempDir Path tempDir) throws IOException {
        Path injected = tempDir.resolve("injected.md");
        Files.writeString(injected, "See `src/main/resources/this-file-does-not-exist.yml` for details.\n");

        assertThat(offendersIn(ROOT, List.of(injected)))
                .containsExactly(injected + " -> src/main/resources/this-file-does-not-exist.yml");
    }

    @Test
    void theLeftBoundaryStopsATruncatedMatchInsideAnotherPathOrUrl() {
        String text = "See `buildSrc/src/main/java/com/cyoda/build/CyodaInstallDecision.java` and "
                + "[a link](https://example.com/other-repo/blob/main/lib/src/utils.js) but "
                + "`src/main/resources/application.yml` still matches on its own.";

        List<String> paths = extractPaths(text);

        // Neither the tail embedded in "buildSrc/src/…" nor the URL's "/src/utils.js" fragment is
        // extracted as if it were a top-level repo path; the standalone backticked path still is.
        assertThat(paths).containsExactly("src/main/resources/application.yml");
    }

    @Test
    void fencedCodeBlocksAreScannedForBarePathsToo() {
        String text = """
                ```bash
                cat src/main/resources/this-one-is-fenced-and-missing.yml
                cat src/main/resources/workflow/example.json
                ```
                """;

        List<String> paths = extractPaths(text).stream()
                .filter(p -> !isPlaceholder(p) && !isAllowlisted(p))
                .toList();

        assertThat(paths).containsExactly("src/main/resources/this-one-is-fenced-and-missing.yml");
    }
}
