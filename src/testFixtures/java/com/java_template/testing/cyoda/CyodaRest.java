package com.java_template.testing.cyoda;

/** Completed in Task 13. */
public final class CyodaRest {
    private final String apiUrl;

    public CyodaRest(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String apiUrl() {
        return apiUrl;
    }
}
