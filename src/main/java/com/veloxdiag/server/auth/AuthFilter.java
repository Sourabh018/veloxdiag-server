package com.veloxdiag.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Resolves "Authorization: Bearer <token>" to a User and stores it in
 * CurrentUserContext for the duration of the request — does NOT reject
 * unauthenticated requests itself (that's AppOwnershipFilter's job, for
 * endpoints that actually carry an applicationName). This filter's only
 * job is identity resolution; a request with no/invalid token just proceeds
 * with CurrentUserContext.get() == null, and /api/auth/register,
 * /api/auth/login, ingestion, and admin endpoints all work fine with no
 * user resolved at all — they don't need one.
 *
 * @Order ensures this runs before AppOwnershipFilter (which depends on
 * CurrentUserContext already being populated) — Spring registers plain
 * Filter beans in undefined order otherwise.
 */
@Component
@Order(10)
public class AuthFilter extends HttpFilter {

    private final UserRepository userRepository;

    public AuthFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring("Bearer ".length()).trim();
                userRepository.findBySessionToken(token).ifPresent(CurrentUserContext::set);
            }
            chain.doFilter(request, response);
        } finally {
            // Same discipline as veloxdiag-starter's QueryCountInspector.clear()
            // — explicit cleanup so a pooled servlet thread never carries one
            // request's resolved user into the next request it handles.
            CurrentUserContext.clear();
        }
    }
}