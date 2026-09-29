package com.java_template.testing.cyoda;

import java.util.LinkedHashMap;
import java.util.Map;

/** A named set of CYODA_* overrides on top of the tier-1 defaults (mock IAM, memory storage). */
public record Profile(String name, Map<String, String> overrides) {

    public static Profile mockMemory() {
        return new Profile(CyodaProfiles.DEFAULT, Map.of());
    }

    public Profile named(String newName) {
        return new Profile(newName, overrides);
    }

    public Profile with(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>(overrides);
        m.put(key, value);
        return new Profile(name, Map.copyOf(m));
    }
}
