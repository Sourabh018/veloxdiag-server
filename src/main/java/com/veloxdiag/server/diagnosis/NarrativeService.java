package com.veloxdiag.server.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class NarrativeService {

    private static final String MODEL = "gemini-flash-latest";
    private static final String API_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=%s";

    private static final String SYSTEM_PROMPT =
            "You are a senior backend engineer reviewing diagnostic findings for one API endpoint. " +
            "Given the findings below (each with a rule type, severity, message, and evidence), write a " +
            "2-4 sentence explanation of the likely root cause. If multiple findings are present, state " +
            "which is the primary driver and which are secondary, using the confidence/ratio data provided " +
            "where available. Do not invent numbers, percentages, or facts that are not present in the " +
            "input. If the evidence is inconclusive, say so plainly rather than guessing. " +
            "Match your confidence language to each finding's stated severity and wording: a LOW-severity " +
            "or 'possible'/'suspected' finding must be described with equally hedged language (e.g. " +
            "'may indicate', 'a possible contributor') — do not upgrade it to definitive phrasing like " +
            "'the primary driver' or 'is caused by' unless the finding itself is HIGH severity or stated " +
            "as confirmed. Never let your narrative sound more certain than the underlying evidence.";

    private final GeminiKeyRotator keyRotator;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public NarrativeService(GeminiKeyRotator keyRotator) {
        this.keyRotator = keyRotator;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public EndpointNarrative generateNarrative(String endpoint, List<DiagnosisFinding> findings) {
        List<String> ruleTypes = findings.stream()
                .map(DiagnosisFinding::getRuleType)
                .collect(Collectors.toList());

        if (findings.isEmpty()) {
            return new EndpointNarrative(endpoint,
                    "No findings are currently present for this endpoint, so there's nothing to explain.",
                    ruleTypes);
        }

        if (!keyRotator.hasKeys()) {
            return new EndpointNarrative(endpoint,
                    "Narrative generation isn't configured (missing GEMINI_API_KEY).", ruleTypes);
        }

        String fullPrompt = SYSTEM_PROMPT + "\n\n" + buildFindingsBlock(endpoint, findings);
        String requestBody;
        try {
            requestBody = buildRequestBody(fullPrompt);
        } catch (Exception e) {
            return new EndpointNarrative(endpoint,
                    "Narrative generation failed: " + e.getClass().getSimpleName(), ruleTypes);
        }

        // Try once per available key. Stops at first non-429 outcome (success or a
        // real error), only advances on 429 so quota exhaustion doesn't burn through
        // keys pointlessly for unrelated failures.
        int attempts = Math.max(1, keyRotator.keyCount());
        String lastFailureBody = null;
        int lastStatus = -1;

        for (int i = 0; i < attempts; i++) {
            String url = String.format(API_URL_TEMPLATE, keyRotator.current());
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String text = extractText(response.body());
                    return new EndpointNarrative(endpoint, text, ruleTypes);
                }

                lastStatus = response.statusCode();
                lastFailureBody = response.body();

                if (response.statusCode() == 429 && keyRotator.rotate()) {
                    continue; // try next key
                }
                break; // non-429 failure, or no more keys to rotate to
            } catch (Exception e) {
                return new EndpointNarrative(endpoint,
                        "Narrative generation failed: " + e.getClass().getSimpleName(), ruleTypes);
            }
        }

        String truncated = lastFailureBody != null && lastFailureBody.length() > 300
                ? lastFailureBody.substring(0, 300) : lastFailureBody;
        return new EndpointNarrative(endpoint,
                "Narrative generation failed (status " + lastStatus + "): " + truncated,
                ruleTypes);
    }

    private String buildFindingsBlock(String endpoint, List<DiagnosisFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("Endpoint: ").append(endpoint).append("\n\nFindings:\n");
        for (DiagnosisFinding f : findings) {
            sb.append("- [").append(f.getRuleType()).append(", severity=").append(f.getSeverity());
            if (f.getConfidence() != null) {
                sb.append(", confidence=").append(f.getConfidence());
            }
            sb.append("] ").append(f.getMessage());
            if (f.getEvidence() != null) {
                sb.append(" Evidence: ").append(f.getEvidence());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildRequestBody(String fullPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();

        ArrayNode contents = root.putArray("contents");
        ObjectNode contentEntry = contents.addObject();
        ArrayNode parts = contentEntry.putArray("parts");
        ObjectNode part = parts.addObject();
        part.put("text", fullPrompt);

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("maxOutputTokens", 2048);
        generationConfig.putObject("thinkingConfig").put("thinkingBudget", 0);

        return objectMapper.writeValueAsString(root);
    }

    private String extractText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && candidates.size() > 0) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray() && parts.size() > 0) {
                String text = parts.get(0).path("text").asText();
                if (!text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return "Narrative generation returned an empty response.";
    }
}