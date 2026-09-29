package com.cyoda.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: `buildLogicTest` used to exec "$rootDir/gradlew" directly, which cannot run under
 * gradlew.bat, so `gradlew.bat build -x integrationTest` failed for every Windows user (T2b).
 * This pins the two commandLine shapes it must choose between.
 */
class GradleWrapperCommandTest {

    @Test
    void onWindowsItRunsTheBatFileThroughCmd() {
        assertThat(GradleWrapperCommand.of(true, "-p", "buildSrc", "test"))
                .containsExactly("cmd", "/c", "gradlew.bat", "-p", "buildSrc", "test");
    }

    @Test
    void elsewhereItRunsTheShellWrapperDirectly() {
        assertThat(GradleWrapperCommand.of(false, "-p", "buildSrc", "test"))
                .containsExactly("./gradlew", "-p", "buildSrc", "test");
    }
}
