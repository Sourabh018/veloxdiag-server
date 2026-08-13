package com.veloxdiag.server.config;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Guards /api/telemetry and /api/slow-query-plans against flooding, keyed by
 * client IP. Deliberately runs BEFORE TelemetryIngestFilter's API-key check
 * (see @Order below) rather than after: keying by IP pre-auth also throttles
 * someone brute-force-guessing a valid X-API-KEY, not just a legitimate app
 * sending too much traffic. Keying by the API key itself instead would miss
 * that case entirely, since a guessed/invalid key is never associated with
 * any real app to throttle against.
 *
 * The budget here is intentionally generous compared to LoginRateLimitFilter
 * — a live application under normal load can legitimately send many
 * telemetry POSTs per second (one per HTTP request it serves), so this is
 * sized to catch actual flooding/abuse, not ordinary traffic.
 */
@Component
@Order(1)
public class IngestRateLimitFilter extends HttpFilter {

    private static final int MAX_REQUESTS = 300;
    private static final long WINDOW_MILLIS = 60_000; // 300 requests/min per IP (~5/sec sustained)

    private final RateLimiter limiter = new RateLimiter(MAX_REQUESTS, WINDOW_MILLIS);

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String path = request.getRequestURI();
        boolean isIngestPath = path.equals("/api/telemetry") || path.equals("/api/slow-query-plans");

        if (!isIngestPath || !"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String key = clientIp(request);

        if (!limiter.tryAcquire(key)) {
            long retryAfterSeconds = limiter.secondsUntilRetry(key);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            // 429 Too Many Requests (RFC 6585) — no HttpServletResponse.SC_* constant
            // exists for this code, it postdates the original Servlet API's constant list.
            response.sendError(429,
                    "Too many ingestion requests from this source. Try again in " + retryAfterSeconds + "s.");
            return;
        }

        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}