package com.veloxdiag.server.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Guards the two open ingestion endpoints (/api/telemetry, /api/slow-query-plans)
// with a shared-secret header check — same "shared secret via env var, never
// hardcoded" pattern already used for ADMIN_RESET_TOKEN. Injected from the
// TELEMETRY_INGEST_TOKEN env var (see application.yaml) — kept separate from
// admin.reset-token deliberately: different purpose, different blast radius
// if either one leaks.
@Component
public class TelemetryIngestFilter extends HttpFilter {

    @Value("${telemetry.ingest-token:}")
    private String ingestToken;

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String path = request.getRequestURI();
        boolean isIngestPath = path.equals("/api/telemetry") || path.equals("/api/slow-query-plans");

        if (isIngestPath && "POST".equalsIgnoreCase(request.getMethod())) {
            if (ingestToken == null || ingestToken.isBlank()) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Ingestion disabled: TELEMETRY_INGEST_TOKEN is not configured on the server.");
                return;
            }
            String provided = request.getHeader("X-API-KEY");
            if (provided == null || !provided.equals(ingestToken)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing X-API-KEY.");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}