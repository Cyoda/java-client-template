package com.java_template.testing.cyoda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Creates a model from sample data, imports its workflow and locks it (entities need a LOCKED model). */
public final class CyodaModelSetup {

    private static final ObjectMapper OM = new ObjectMapper();

    private CyodaModelSetup() {
    }

    public static void createModel(CyodaRest rest, String model, int version, JsonNode sampleData, JsonNode workflow) {
        rest.post("model/import/JSON/SAMPLE_DATA/" + model + "/" + version, sampleData).requireSuccess();
        rest.post("model/" + model + "/" + version + "/changeLevel/STRUCTURAL", null).requireSuccess();
        importWorkflow(rest, model, version, workflow);
        rest.put("model/" + model + "/" + version + "/lock", null).requireSuccess();
    }

    public static void importWorkflow(CyodaRest rest, String model, int version, JsonNode workflow) {
        ObjectNode body = OM.createObjectNode();
        body.put("importMode", "REPLACE");
        body.putArray("workflows").add(workflow);
        rest.post("model/" + model + "/" + version + "/workflow/import", body).requireSuccess();
    }
}
