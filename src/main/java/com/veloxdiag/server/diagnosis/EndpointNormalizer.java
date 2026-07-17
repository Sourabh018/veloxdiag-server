package com.veloxdiag.server.diagnosis;

import java.util.regex.Pattern;

public class EndpointNormalizer {

    // Matches standard UUID format: 8-4-4-4-12 hex characters
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    // Matches a path segment that is purely numeric (e.g. /api/exams/1234)
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("(?<=/)\\d+(?=/|$)");

    private EndpointNormalizer() {
        // utility class, no instances
    }

    /**
     * Replaces UUID and purely-numeric path segments with {id}, so that
     * /api/exams/9a352dba-0002-4d6b-a0e2-758d8ed33ac7 and
     * /api/exams/f33f69a2-d9b1-40af-b1b9-b7e3fb256a85
     * both normalize to /api/exams/{id} and can be grouped together.
     *
     * Does NOT touch trailing action segments like /submit or /save —
     * only replaces the ID segment itself, e.g.:
     * /api/exams/9a352dba-.../submit -> /api/exams/{id}/submit
     */
    public static String normalize(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        String normalized = UUID_PATTERN.matcher(endpoint).replaceAll("{id}");
        normalized = NUMERIC_ID_PATTERN.matcher(normalized).replaceAll("{id}");
        return normalized;
    }
}