package com.cyoda.build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the {@code commandLine} for invoking the Gradle wrapper as a child process, from a
 * directory that already is the wrapper's own root. {@code "$rootDir/gradlew"} cannot run under
 * {@code gradlew.bat} on Windows, so Windows must instead invoke {@code gradlew.bat} through
 * {@code cmd /c}.
 */
public final class GradleWrapperCommand {

    private GradleWrapperCommand() {
    }

    public static List<String> of(boolean windows, String... args) {
        List<String> command = new ArrayList<>();
        if (windows) {
            command.add("cmd");
            command.add("/c");
            command.add("gradlew.bat");
        } else {
            command.add("./gradlew");
        }
        command.addAll(Arrays.asList(args));
        return command;
    }
}
