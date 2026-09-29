package com.cyoda.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts Java generator options into a vendored cyoda-go .proto file (which declares only
 * go_package). Fails loudly when the file already declares a Java option this build does
 * not manage or declares one with a different value, so contract drift is never silent.
 */
public final class ProtoOptionInjector {

    public static final Map<String, String> CYODA_API_OPTIONS = Map.of("java_multiple_files", "true");

    /** Matches the cloudevents-protobuf library, whose CloudEvent comes from spec.proto (outer class Spec). */
    public static final Map<String, String> CLOUDEVENTS_OPTIONS = Map.of(
            "java_multiple_files", "true",
            "java_package", "\"io.cloudevents.v1.proto\"",
            "java_outer_classname", "\"Spec\"");

    /** Emission order for injected options; any other managed option follows alphabetically. */
    private static final List<String> CANONICAL_ORDER = List.of("java_multiple_files", "java_package", "java_outer_classname");

    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^package\\s+[A-Za-z0-9_.]+\\s*;[ \\t]*$");
    private static final Pattern JAVA_OPTION = Pattern.compile("(?m)^\\s*option\\s+(java_[a-z_]+)\\s*=\\s*([^;]+);");

    private ProtoOptionInjector() {
    }

    public static String inject(String proto, String label, Map<String, String> options) {
        Map<String, String> existing = new HashMap<>();
        Matcher option = JAVA_OPTION.matcher(proto);
        while (option.find()) {
            existing.put(option.group(1), option.group(2).trim());
        }
        existing.forEach((name, value) -> {
            String wanted = options.get(name);
            if (wanted == null || !wanted.equals(value)) {
                throw new IllegalStateException(label + " declares option " + name + " = " + value
                        + ", which conflicts with the Java options this build injects; update ProtoOptionInjector");
            }
        });

        Matcher pkg = PACKAGE_LINE.matcher(proto);
        if (!pkg.find()) {
            throw new IllegalStateException(label + ": no package declaration");
        }
        int insertAt = pkg.end();
        if (pkg.find()) {
            throw new IllegalStateException(label + ": more than one package declaration");
        }

        List<String> order = new ArrayList<>(CANONICAL_ORDER);
        new TreeSet<>(options.keySet()).stream().filter(k -> !order.contains(k)).forEach(order::add);
        StringBuilder inserted = new StringBuilder();
        for (String name : order) {
            String value = options.get(name);
            if (value != null && !existing.containsKey(name)) {
                inserted.append("\noption ").append(name).append(" = ").append(value).append(';');
            }
        }
        return proto.substring(0, insertAt) + inserted + proto.substring(insertAt);
    }
}
