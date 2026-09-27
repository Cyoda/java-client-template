package com.java_template.it.support;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.cyoda.cloud.api.event.common.ModelSpec;

import java.util.List;

/** Test entity whose model name is chosen per test class, so tests never share a model. */
@Data
@NoArgsConstructor
public class ItThing implements CyodaEntity {

    @JsonIgnore
    private String model;
    private String name;
    private Integer amount;
    private List<String> tags;
    private String note;
    private String ref;

    public static ItThing of(String model, String name, int amount) {
        ItThing t = new ItThing();
        t.model = model;
        t.name = name;
        t.amount = amount;
        t.tags = List.of("t");
        t.note = "";
        t.ref = "";
        return t;
    }

    public ItThing in(String modelName) {
        this.model = modelName;
        return this;
    }

    @Override
    public OperationSpecification getModelKey() {
        return new OperationSpecification.Entity(new ModelSpec().withName(model).withVersion(1), "ItThing");
    }

    public static JsonNode sampleData() {
        try {
            return new ObjectMapper().readTree("""
                    {"name":"sample","amount":1,"tags":["a","b"],"note":"n","ref":"r"}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
