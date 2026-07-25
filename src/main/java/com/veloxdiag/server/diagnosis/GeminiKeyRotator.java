package com.veloxdiag.server.diagnosis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds a list of Gemini API keys and rotates to the next one when the
 * current key hits a 429 (quota exceeded). Shared by NarrativeService and
 * RecommendationService so both features benefit from the same pool.
 *
 * Config: gemini.api.key can be a single key OR a comma-separated list.
 * Example application.properties:
 *   gemini.api.key=${GEMINI_KEY_1},${GEMINI_KEY_2},${GEMINI_KEY_3}
 */
@Component
public class GeminiKeyRotator {

    private final List<String> keys;
    private final AtomicInteger index = new AtomicInteger(0);

    public GeminiKeyRotator(@Value("${gemini.api.key:}") String keysCsv) {
        List<String> parsed = new ArrayList<>();
        if (keysCsv != null && !keysCsv.isBlank()) {
            for (String k : keysCsv.split(",")) {
                String trimmed = k.trim();
                if (!trimmed.isBlank()) {
                    parsed.add(trimmed);
                }
            }
        }
        this.keys = parsed;
    }

    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    public int keyCount() {
        return keys.size();
    }

    public String current() {
        if (keys.isEmpty()) return null;
        return keys.get(Math.floorMod(index.get(), keys.size()));
    }

    /**
     * Advances to the next key. Returns false if there's only one key
     * (nothing to rotate to) so callers know not to bother retrying.
     */
    public boolean rotate() {
        if (keys.size() <= 1) return false;
        index.incrementAndGet();
        return true;
    }
}