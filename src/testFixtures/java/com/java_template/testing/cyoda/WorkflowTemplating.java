package com.java_template.testing.cyoda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Sets every processor's and function criterion's calculationNodesTags to the Spring context's
 * unique tag. A missing or empty tag is refused: an empty list matches every member of the
 * tenant, so another context's member could receive the callout (spec §6.1).
 */
public final class WorkflowTemplating {

    private static final ObjectMapper OM = new ObjectMapper();

    private WorkflowTemplating() {
    }

    public static ObjectNode load(String classpathResource, String tag) {
        try (InputStream in = WorkflowTemplating.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("no classpath resource " + classpathResource);
            }
            return applyTag(OM.readTree(in), tag);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ObjectNode applyTag(JsonNode workflow, String tag) {
        ObjectNode copy = workflow.deepCopy();
        String wf = copy.path("name").asText("?");
        copy.path("states").forEach(state -> state.path("transitions").forEach(t -> {
            t.path("processors").forEach(p -> retag(p, wf, "processor '" + p.path("name").asText() + "'", tag));
            JsonNode criterion = t.path("criterion");
            if ("function".equals(criterion.path("type").asText())) {
                JsonNode fn = criterion.path("function");
                retag(fn, wf, "criterion function '" + fn.path("name").asText() + "'", tag);
            }
        }));
        return copy;
    }

    private static void retag(JsonNode owner, String wf, String what, String tag) {
        JsonNode config = owner.get("config");
        JsonNode tags = config == null ? null : config.get("calculationNodesTags");
        if (tags == null) {
            throw new IllegalArgumentException("workflow '" + wf + "': " + what + " has no config.calculationNodesTags");
        }
        if (tags.asText().isBlank()) {
            throw new IllegalArgumentException("workflow '" + wf + "': " + what
                    + " has empty calculationNodesTags, which matches every member of the tenant");
        }
        ((ObjectNode) config).put("calculationNodesTags", tag);
    }
}
