package com.veloxdiag.server.config;

import org.springframework.stereotype.Component;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Guards /api/auth/login and /api/auth/register against brute-force/spam —
 * a documented known gap called out directly in AuthController's own
 * javadoc ("No rate limiting on login attempts").
 *
 * Keyed by client IP rather than the submitted email: an email-keyed limit
 * would let an attacker brute-force ANY OTHER account by simply rotating
 * emails per request, while a legitimate user who mistypes their own
 * password a few times only ever affects their own bucket either way.
 * IP-keying is the correct choice for this specific attack shape.
 *
 * Limits are intentionally asymmetric: login gets a tighter budget than
 * register, since a stolen/guessed-password attack targets login
 * specifically and repeatedly, while spam registration is a lower-frequency
 * nuisance by comparison.
 */
@Component
public class LoginRateLimitFilter extends HttpFilter {

    private static final int LOGIN_MAX_ATTEMPTS = 5;
    private static final long LOGIN_WINDOW_MILLIS = 60_000; // 5 attempts per minute per IP

    private static final int REGISTER_MAX_ATTEMPTS = 10;
    private static final long REGISTER_WINDOW_MILLIS = 60_000; // 10 attempts per minute per IP

    private final RateLimiter loginLimiter = new RateLimiter(LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW_MILLIS);
    private final RateLimiter registerLimiter = new RateLimiter(REGISTER_MAX_ATTEMPTS, REGISTER_WINDOW_MILLIS);

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String path = request.getRequestURI();
        boolean isLogin = path.equals("/api/auth/login");
        boolean isRegister = path.equals("/api/auth/register");

        if ((!isLogin && !isRegister) || !"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        RateLimiter limiter = isLogin ? loginLimiter : registerLimiter;
        String key = clientIp(request);

        if (!limiter.tryAcquire(key)) {
            long retryAfterSeconds = limiter.secondsUntilRetry(key);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            // 429 Too Many Requests (RFC 6585) — no HttpServletResponse.SC_* constant
            // exists for this code, it postdates the original Servlet API's constant list.
            response.sendError(429,
                    "Too many " + (isLogin ? "login" : "registration") + " attempts. Try again in "
                            + retryAfterSeconds + "s.");
            return;
        }

        chain.doFilter(request, response);
    }

    // Prefers X-Forwarded-For (set by a reverse proxy/load balancer in front
    // of the app, e.g. on most cloud hosts) over the raw socket address,
    // which would otherwise just be the proxy's own IP for every request.
    // Falls back to getRemoteAddr() for direct/local connections.
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}