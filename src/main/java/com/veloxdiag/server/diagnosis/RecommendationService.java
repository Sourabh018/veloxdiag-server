package com.veloxdiag.server.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Turns findings into concrete, actionable fixes.
 *
 * Deterministic by default (template text per ruleType) — matches the project's
 * established pattern of the rule engine being the source of truth, not the LLM.
 * For MISSING_INDEX_CANDIDATE specifically, if we have a real captured
 * SlowQueryPlan (actual sqlText + EXPLAIN output) for the endpoint, an LLM call
 * turns that into a concrete example CREATE INDEX statement. If no plan is
 * captured yet, or the API key is missing, or the call fails, we fall back to
 * the generic template — never block the page on the AI call.
 *
 * HIGH_ERROR_RATE / SERVER_ERROR findings intentionally produce no recommendation:
 * there's no generic fix for "something threw an error" without knowing the
 * actual exception, so suggesting one would be a fabricated, unhelpful guess.
 */
@Service
public class RecommendationService {

    private static final String MODEL = "gemini-flash-latest";
    private static final String API_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=%s";

    private static final String INDEX_SYSTEM_PROMPT =
            "You are a senior backend engineer. You are given a slow SQL query and its EXPLAIN plan " +
            "(showing a sequential scan). Suggest ONE concrete CREATE INDEX statement that would likely " +
            "fix it. Base the column choice only on the WHERE/JOIN/ORDER BY columns actually visible in " +
            "the SQL text — never invent a table or column name that isn't present in the input. " +
            "Respond with the CREATE INDEX statement on its own line, then a single sentence explaining why, " +
            "in this exact format, no other text:\n" +
            "SQL: <statement>\n" +
            "WHY: <one sentence>";

    private final DiagnosisService diagnosisService;
    private final SlowQueryPlanRepository slowQueryPlanRepository;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RecommendationService(DiagnosisService diagnosisService,
                                  SlowQueryPlanRepository slowQueryPlanRepository,
                                  @Value("${gemini.api.key:}") String apiKey) {
        this.diagnosisService = diagnosisService;
        this.slowQueryPlanRepository = slowQueryPlanRepository;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    public List<Recommendation> getRecommendations() {
        List<DiagnosisFinding> allFindings = diagnosisService.runDiagnosis();

        Map<String, List<DiagnosisFinding>> byEndpoint = allFindings.stream()
                .collect(Collectors.groupingBy(DiagnosisFinding::getEndpoint));

        List<Recommendation> recommendations = new ArrayList<>();

        for (Map.Entry<String, List<DiagnosisFinding>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<String> ruleTypes = entry.getValue().stream()
                    .map(DiagnosisFinding::getRuleType)
                    .collect(Collectors.toList());

            for (DiagnosisFinding finding : entry.getValue()) {
                Recommendation rec = buildRecommendation(endpoint, finding, ruleTypes);
                if (rec != null) {
                    recommendations.add(rec);
                }
            }
        }

        // Worst first
        recommendations.sort((a, b) -> severityRank(b.getSeverity()) - severityRank(a.getSeverity()));

        return recommendations;
    }

    private Recommendation buildRecommendation(String endpoint, DiagnosisFinding finding, List<String> relatedFindings) {
        switch (finding.getRuleType()) {

            case "MISSING_INDEX_CANDIDATE":
                return buildIndexRecommendation(endpoint, finding, relatedFindings);

            case "POSSIBLE_N_PLUS_ONE":
                return new Recommendation(
                        endpoint, finding.getSeverity(), finding.getRuleType(),
                        "Batch or eager-fetch the related entity instead of querying per row",
                        "This endpoint issues a growing number of near-identical queries per request, " +
                        "the classic N+1 shape. In JPA/Hibernate, fix it with a JOIN FETCH in the repository " +
                        "query, an @EntityGraph on the method, or a batch-fetch-size hint — whichever fits " +
                        "the actual relationship being loaded. VeloxDiag doesn't know the exact entity, so " +
                        "verify against the repository method backing this endpoint before applying.",
                        "// Example — replace the per-row lookup with a single fetch join:\n" +
                        "@Query(\"SELECT e FROM Entity e JOIN FETCH e.relatedEntity WHERE ...\")",
                        false, relatedFindings
                );

            case "SLOW_REQUEST":
                // Only recommend for standalone slow-request findings — if N+1 or missing-index
                // findings are also present for this endpoint, those carry the concrete fix instead.
                if (relatedFindings.contains("POSSIBLE_N_PLUS_ONE") || relatedFindings.contains("MISSING_INDEX_CANDIDATE")) {
                    return null;
                }
                return new Recommendation(
                        endpoint, finding.getSeverity(), finding.getRuleType(),
                        "Profile this endpoint — no query-level cause identified yet",
                        "This endpoint is slow but doesn't show a query-count spike or a consistently-slow " +
                        "index pattern. The cause is likely outside the database layer: an external API call, " +
                        "serialization overhead, or synchronous work that could be made async. Check the " +
                        "Query Analyzer and Slow Queries pages for this endpoint for more evidence before guessing further.",
                        null, false, relatedFindings
                );

            case "HIGH_ERROR_RATE":
            case "SERVER_ERROR":
            case "ROOT_CAUSE_CORRELATION":
                // Deliberately no recommendation: no honest generic fix exists for "error rate is
                // high" or "a correlation was found" without knowing the actual exception/cause.
                return null;

            default:
                // Anything else is a custom rule defined via RuleDefinitionEntity / RuleEngineService —
                // an open-ended, user-configurable set we can't have a tailored template for in advance.
                // Rather than silently dropping it (which would make custom rules invisible on this
                // page forever), surface it plainly using the rule's own message, clearly labeled as
                // untemplated so it reads as "here's the finding" rather than "here's a vetted fix."
                return new Recommendation(
                        endpoint, finding.getSeverity(), finding.getRuleType(),
                        "Custom rule triggered — no specific fix template available yet",
                        "This finding comes from a custom rule (\"" + finding.getRuleType() + "\") without a " +
                        "built-in recommendation template. Underlying finding: " + finding.getMessage(),
                        null, false, relatedFindings
                );
        }
    }

    private Recommendation buildIndexRecommendation(String endpoint, DiagnosisFinding finding, List<String> relatedFindings) {
        String genericDetail =
                "This endpoint is consistently slow on every call rather than only under load — a pattern " +
                "often caused by a missing database index on a frequently filtered or joined column. " +
                "Run EXPLAIN on the endpoint's queries (see Slow Queries page) to confirm a sequential scan " +
                "before adding an index.";
        String genericCode = "-- No captured query plan yet for this endpoint.\n" +
                "-- Check the Slow Queries page once a plan is captured for a concrete suggestion.";

        List<SlowQueryPlan> plans = slowQueryPlanRepository.findTop3ByEndpointOrderByTimestampDesc(endpoint);
        SlowQueryPlan seqScanPlan = plans.stream().filter(SlowQueryPlan::isContainsSeqScan).findFirst().orElse(null);

        if (seqScanPlan == null || apiKey == null || apiKey.isBlank()) {
            return new Recommendation(endpoint, finding.getSeverity(), finding.getRuleType(),
                    "Add a database index — pattern suggests a missing index",
                    genericDetail, genericCode, false, relatedFindings);
        }

        try {
            String aiResult = callGeminiForIndex(seqScanPlan);
            if (aiResult != null) {
                String[] parts = parseSqlWhy(aiResult);
                return new Recommendation(endpoint, finding.getSeverity(), finding.getRuleType(),
                        "Add a database index — suggested statement below",
                        parts[1] != null ? parts[1] : genericDetail,
                        parts[0] != null ? parts[0] : genericCode,
                        parts[0] != null, relatedFindings);
            }
        } catch (Exception e) {
            // fall through to generic
        }

        return new Recommendation(endpoint, finding.getSeverity(), finding.getRuleType(),
                "Add a database index — pattern suggests a missing index",
                genericDetail, genericCode, false, relatedFindings);
    }

    private String callGeminiForIndex(SlowQueryPlan plan) throws Exception {
        String prompt = INDEX_SYSTEM_PROMPT + "\n\nSQL:\n" + plan.getSqlText() +
                "\n\nEXPLAIN PLAN:\n" + plan.getExplainPlan();

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode contentEntry = contents.addObject();
        ArrayNode parts = contentEntry.putArray("parts");
        parts.addObject().put("text", prompt);
        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("maxOutputTokens", 400);

        String url = String.format(API_URL_TEMPLATE, apiKey);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(root)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonNode responseRoot = objectMapper.readTree(response.body());
        JsonNode candidates = responseRoot.path("candidates");
        if (candidates.isArray() && candidates.size() > 0) {
            JsonNode textParts = candidates.get(0).path("content").path("parts");
            if (textParts.isArray() && textParts.size() > 0) {
                String text = textParts.get(0).path("text").asText();
                return text.isBlank() ? null : text.trim();
            }
        }
        return null;
    }

    // returns [sql, why]
    private String[] parseSqlWhy(String raw) {
        String sql = null;
        String why = null;
        for (String line : raw.split("\n")) {
            if (line.startsWith("SQL:")) {
                sql = line.substring(4).trim();
            } else if (line.startsWith("WHY:")) {
                why = line.substring(4).trim();
            }
        }
        return new String[]{sql, why};
    }

    private int severityRank(String severity) {
        if (severity == null) return 0;
        switch (severity) {
            case "HIGH": return 3;
            case "MEDIUM": return 2;
            case "LOW": return 1;
            default: return 0;
        }
    }
}