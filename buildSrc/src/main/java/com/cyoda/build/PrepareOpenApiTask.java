package com.cyoda.build;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;

/** Writes the patched copy of the vendored openapi.yaml that openapi-generator reads (spec §3.3.3). */
public abstract class PrepareOpenApiTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSourceFile();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void prepare() throws IOException {
        YAMLMapper yaml = new YAMLMapper();
        ObjectNode spec = (ObjectNode) yaml.readTree(getSourceFile().get().getAsFile());
        OpenApiPatcher.patch(spec);
        var out = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        yaml.writeValue(out.toFile(), spec);
    }
}
