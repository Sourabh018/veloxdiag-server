package com.veloxdiag.server.auth;

/**
 * Holds the resolved User for the duration of one request, set by AuthFilter
 * and read by AppOwnershipFilter / any controller that needs to know who's
 * making the request. Same ThreadLocal-with-explicit-clear() pattern as
 * veloxdiag-starter's QueryCountInspector — deliberately kept consistent
 * with that existing, already-proven approach rather than introducing a
 * different mechanism (e.g. Spring Security's SecurityContextHolder) for
 * just this one piece.
 *
 * clear() MUST be called in a finally block by whatever sets it (see
 * AuthFilter), for the same reason QueryCountInspector's cleanup matters:
 * servlet containers reuse threads across requests, so a forgotten clear()
 * would leak one request's user identity into the next request handled by
 * the same pooled thread.
 */
public class CurrentUserContext {

    private static final ThreadLocal<User> CURRENT_USER = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    public static void set(User user) {
        CURRENT_USER.set(user);
    }

    public static User get() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}