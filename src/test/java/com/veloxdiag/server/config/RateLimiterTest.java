package com.veloxdiag.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    @DisplayName("allows requests up to the configured max within the window")
    void allowsUpToMax() {
        RateLimiter limiter = new RateLimiter(3, 60_000);
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).isTrue();
    }

    @Test
    @DisplayName("rejects the request once the max is exceeded within the window")
    void rejectsBeyondMax() {
        RateLimiter limiter = new RateLimiter(2, 60_000);
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).isFalse(); // 3rd request, over the limit of 2
    }

    @Test
    @DisplayName("tracks separate keys independently")
    void tracksKeysIndependently() {
        RateLimiter limiter = new RateLimiter(1, 60_000);
        assertThat(limiter.tryAcquire("ip-a")).isTrue();
        assertThat(limiter.tryAcquire("ip-a")).isFalse(); // ip-a now exhausted
        assertThat(limiter.tryAcquire("ip-b")).isTrue(); // ip-b has its own separate budget
    }

    @Test
    @DisplayName("allows a request again once the window has fully elapsed")
    void allowsAgainAfterWindowElapses() throws InterruptedException {
        // very short window so the test doesn't need to sleep long
        RateLimiter limiter = new RateLimiter(1, 200);
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).isFalse();

        Thread.sleep(250); // let the 200ms window fully elapse

        assertThat(limiter.tryAcquire("k")).isTrue();
    }

    @Test
    @DisplayName("secondsUntilRetry returns 0 for a key that has never been used")
    void secondsUntilRetryZeroForUnknownKey() {
        RateLimiter limiter = new RateLimiter(1, 60_000);
        assertThat(limiter.secondsUntilRetry("never-seen")).isEqualTo(0);
    }

    @Test
    @DisplayName("secondsUntilRetry returns a positive, bounded value once the limit is hit")
    void secondsUntilRetryPositiveWhenExhausted() {
        RateLimiter limiter = new RateLimiter(1, 10_000); // 10 second window
        limiter.tryAcquire("k");
        limiter.tryAcquire("k"); // rejected, but doesn't matter for this check

        long retryAfter = limiter.secondsUntilRetry("k");
        assertThat(retryAfter).isGreaterThan(0).isLessThanOrEqualTo(10);
    }
}