package com.java_template.common.call;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * ABOUTME: The credential and transaction for exactly one Cyoda call (spec §4.2). Built only by
 * {@link CyodaCallContexts} and passed explicitly through every stage of an operation.
 */
public record CyodaCallContext(Credential credential, String txToken) {

    public sealed interface Credential permits None, M2m, Forward {
    }

    /** No Authorization header (cyoda-go mock IAM). */
    public record None() implements Credential {
    }

    /** The M2M service-account token. */
    public record M2m() implements Credential {
    }

    /** A user's own IdP token, sent unchanged. */
    public record Forward(String token) implements Credential {
        @Override
        public String toString() {
            return "Forward[token=<redacted>]";
        }
    }

    public static CyodaCallContext none() {
        return new CyodaCallContext(new None(), null);
    }

    public static CyodaCallContext m2m() {
        return new CyodaCallContext(new M2m(), null);
    }

    public static CyodaCallContext forward(String token) {
        return new CyodaCallContext(new Forward(token), null);
    }

    public CyodaCallContext withTxToken(String token) {
        return new CyodaCallContext(credential, token);
    }

    public boolean isJoined() {
        return txToken != null;
    }

    /** Stable, secret-free identity of this context, for cache keys. */
    public String fingerprint() {
        String cred = switch (credential) {
            case None n -> "none";
            case M2m m -> "m2m";
            case Forward f -> "fwd:" + sha256(f.token());
        };
        return txToken == null ? cred : cred + "|tx:" + sha256(txToken);
    }

    @Override
    public String toString() {
        return "CyodaCallContext[" + credential + (txToken == null ? "" : ", tx=<redacted>") + "]";
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
