package com.cyoda.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Copies the vendored event-schema tree into build/cyoda-schema, transformed for jsonschema2pojo (spec §3.3.2). */
public abstract class PrepareEventSchemasTask extends DefaultTask {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void prepare() throws IOException {
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path src = getSourceDir().get().getAsFile().toPath();
        Path out = getOutputDir().get().getAsFile().toPath();
        getProject().delete(out.toFile());

        List<JsonNode> orderByItems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String label = src.relativize(file).toString();
                ObjectNode schema = (ObjectNode) om.readTree(file.toFile());
                EventSchemaTransformer.transform(schema, label);
                schema.findParents("orderBy").forEach(parent -> {
                    JsonNode items = parent.get("orderBy").get("items");
                    if (items != null && items.has("javaType")) {
                        orderByItems.add(items);
                    }
                });
                Path target = out.resolve(label);
                Files.createDirectories(target.getParent());
                om.writeValue(target.toFile(), schema);
            }
        }
        for (JsonNode items : orderByItems) {
            if (!items.equals(orderByItems.getFirst())) {
                throw new IllegalStateException("orderBy item schemas differ between event schemas; "
                        + "a shared javaType would drop one of them. Update EventSchemaTransformer.");
            }
        }
    }
}
