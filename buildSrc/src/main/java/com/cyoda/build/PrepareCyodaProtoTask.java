package com.cyoda.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies the vendored protos into build/cyoda-proto/{main,include} with Java options injected (spec §3.3.1). */
public abstract class PrepareCyodaProtoTask extends DefaultTask {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void prepare() throws IOException {
        Path src = getSourceDir().get().getAsFile().toPath();
        Path out = getOutputDir().get().getAsFile().toPath();
        getProject().delete(out.toFile());

        Path api = src.resolve("cyoda/cyoda-cloud-api.proto");
        Path ce = src.resolve("cloudevents/cloudevents.proto");
        write(out.resolve("main/cyoda/cyoda-cloud-api.proto"),
                ProtoOptionInjector.inject(Files.readString(api), api.toString(), ProtoOptionInjector.CYODA_API_OPTIONS));
        write(out.resolve("include/cloudevents/cloudevents.proto"),
                ProtoOptionInjector.inject(Files.readString(ce), ce.toString(), ProtoOptionInjector.CLOUDEVENTS_OPTIONS));
    }

    private static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
