package com.veloxdiag.server.config;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple in-memory sliding-window rate limiter, keyed by an arbitrary
 * string (client IP, API key, email — whatever the caller wants to bucket
 * requests by). Deliberately not backed by a dependency like Bucket4j: this
 * is a single-instance deployment (see IndexAdvisorService's own
 * ConcurrentHashMap-per-app pattern for the same reasoning elsewhere in this
 * codebase) — if VeloxDiag is ever run across multiple server instances
 * behind a load balancer, this in-memory approach stops being correct and
 * would need to move to a shared store (Redis, etc). Documented here rather
 * than silently wrong.
 *
 * Each key holds a deque of request timestamps within the current window;
 * old timestamps are evicted lazily on each check rather than via a
 * background sweep, so idle keys don't cost anything between requests.
 */
public class RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Deque<Long>> requestLog = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    /**
     * Returns true and records the request if the key is still under its
     * limit for the current window; returns false (and does NOT record) if
     * the key has already hit the limit — callers should reject the request
     * on false rather than let it through.
     */
    public boolean tryAcquire(String key) {
        long now = Instant.now().toEpochMilli();
        Deque<Long> timestamps = requestLog.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            evictOld(timestamps, now);
            if (timestamps.size() >= maxRequests) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /** How many seconds until this key's oldest request ages out of the window (for a Retry-After hint). */
    public long secondsUntilRetry(String key) {
        Deque<Long> timestamps = requestLog.get(key);
        if (timestamps == null || timestamps.isEmpty()) {
            return 0;
        }
        synchronized (timestamps) {
            Long oldest = timestamps.peekFirst();
            if (oldest == null) return 0;
            long retryAtMillis = oldest + windowMillis;
            long remainingMillis = retryAtMillis - Instant.now().toEpochMilli();
            return Math.max(0, remainingMillis / 1000);
        }
    }

    private void evictOld(Deque<Long> timestamps, long now) {
        long cutoff = now - windowMillis;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }
    }
}