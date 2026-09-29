package com.java_template.testing.cyoda;

/** The profiles tier-1 tests may request by name via @CyodaIntegrationTest(profile = …). */
public final class CyodaProfiles {

    public static final String DEFAULT = "default";
    /** Keep-alive 1 s / eviction 3 s — only for ComputeMemberJoinIT (spec §6.2). */
    public static final String KEEPALIVE_SHORT = "keepalive-short";

    private CyodaProfiles() {
    }

    public static Profile byName(String name) {
        return switch (name) {
            case DEFAULT -> Profile.mockMemory();
            case KEEPALIVE_SHORT -> Profile.mockMemory().named(KEEPALIVE_SHORT)
                    .with("CYODA_KEEPALIVE_INTERVAL", "1")
                    .with("CYODA_KEEPALIVE_TIMEOUT", "3");
            default -> throw new IllegalArgumentException("unknown cyoda test profile '" + name + "'");
        };
    }
}
