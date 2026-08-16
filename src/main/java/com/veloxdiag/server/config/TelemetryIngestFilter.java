package com.veloxdiag.server.config;

import java.io.IOException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloxdiag.server.auth.Application;
import com.veloxdiag.server.auth.ApplicationRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Guards the open ingestion endpoints (/api/telemetry, /api/slow-query-plans,
// /api/jvm-metrics).
// Two accepted forms of X-API-KEY, checked in order:
//   1. Per-app key (Application.ingestApiKey, see ApplicationController) — the
//      real mechanism now that multi-tenancy exists. The key must belong to a
//      registered app AND the request body's applicationName must match that
//      app's name, otherwise app A's key could still post telemetry under app
//      B's name.
//   2. Legacy shared secret (TELEMETRY_INGEST_TOKEN env var) — kept as a
//      fallback ONLY so already-deployed, not-yet-registered apps (CET_CELL)
//      don't go dark mid-migration. Remove once every real app has been
//      registered via POST /api/applications and switched to its own key.
// Runs after IngestRateLimitFilter (see its @Order(1) + javadoc for why
// IP-based rate limiting has to come first, not after key validation).
@Component
@Order(2)
public class TelemetryIngestFilter extends HttpFilter {

    @Value("${telemetry.ingest-token:}")
    private String legacyIngestToken;

    private final ApplicationRepository applicationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TelemetryIngestFilter(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String path = request.getRequestURI();
        boolean isIngestPath = path.equals("/api/telemetry") || path.equals("/api/slow-query-plans")
                || path.equals("/api/jvm-metrics");

        if (!isIngestPath || !"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader("X-API-KEY");
        if (provided == null || provided.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-API-KEY.");
            return;
        }

        // Body must be cached before either check reads it, so the controller
        // downstream can still deserialize it later.
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        Optional<Application> app = applicationRepository.findByIngestApiKey(provided);
        if (app.isPresent()) {
            String bodyAppName = extractApplicationName(cachedRequest.getCachedBody());
            if (bodyAppName == null || !bodyAppName.equals(app.get().getName())) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "X-API-KEY does not match the applicationName in the request body.");
                return;
            }
            chain.doFilter(cachedRequest, response);
            return;
        }

        // Not a known per-app key — fall back to the legacy shared secret.
        if (legacyIngestToken != null && !legacyIngestToken.isBlank() && provided.equals(legacyIngestToken)) {
            chain.doFilter(cachedRequest, response);
            return;
        }

        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid X-API-KEY.");
    }

    private String extractApplicationName(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode nameNode = node.get("applicationName");
            return nameNode == null ? null : nameNode.asText(null);
        } catch (IOException e) {
            return null;
        }
    }
}