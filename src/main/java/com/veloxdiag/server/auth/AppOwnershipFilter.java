package com.veloxdiag.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * The actual isolation enforcement point for multi-tenancy — everything
 * else (User, Application, AuthFilter) exists to make this one check
 * possible: if a request carries an "applicationName" query parameter, the
 * currently authenticated user (see CurrentUserContext, set by AuthFilter,
 * which MUST run first — see its @Order) must own that application in the
 * Application registry, or the request is rejected with 403.
 *
 * Deliberately a single central filter rather than editing every individual
 * controller/service that takes an applicationName param — smaller surface
 * area, one place to audit, and new endpoints get this protection "for
 * free" just by using the same query parameter name every existing endpoint
 * already uses.
 *
 * BACKWARD-COMPATIBLE MIGRATION BEHAVIOR (important — read before enabling):
 * If the requested applicationName has NO row in the Application registry
 * at all (e.g. "CET_CELL" before anyone has run POST /api/applications for
 * it), this filter allows the request through unchanged — same "disabled
 * until configured" pattern as DashboardAccessFilter. This means existing
 * deployments don't break the moment this filter ships; isolation only
 * activates for an application once someone has actually registered it.
 * Until CET_CELL is registered via ApplicationController, anyone can still
 * read its data — that's a real, temporary gap during migration, not a
 * permanent design choice. Register every existing application promptly
 * after deploying this to close it.
 */
@Component
@Order(20)
public class AppOwnershipFilter extends HttpFilter {

    private static final String[] EXCLUDED_PREFIXES = {
            "/api/telemetry",
            "/api/slow-query-plans",
            "/api/admin",
            "/api/auth",
            "/api/applications",
            "/actuator"
    };

    private final ApplicationRepository applicationRepository;

    public AppOwnershipFilter(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String path = request.getRequestURI();
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                chain.doFilter(request, response);
                return;
            }
        }

        String applicationName = request.getParameter("applicationName");
        if (applicationName == null || applicationName.isBlank()) {
            // No applicationName param on this request at all — nothing to
            // scope, let it through (e.g. an endpoint that doesn't take one).
            chain.doFilter(request, response);
            return;
        }

        Optional<Application> app = applicationRepository.findByName(applicationName);
        if (app.isEmpty()) {
            // Not yet registered — see migration note in class javadoc.
            chain.doFilter(request, response);
            return;
        }

        User current = CurrentUserContext.get();
        if (current == null || !app.get().getOwnerUserId().equals(current.getId())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "You do not have access to application '" + applicationName + "'.");
            return;
        }

        chain.doFilter(request, response);
    }
}