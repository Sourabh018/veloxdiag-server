package com.veloxdiag.server.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
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
 * Template text now weaves in each finding's real evidence numbers (sampleCount,
 * averageQueryCount, maxQueryCount, averageDurationMs) so two endpoints hitting the
 * same rule don't render identical copy-paste paragraphs.
 *
 * For MISSING_INDEX_CANDIDATE specifically, if we have a real captured SlowQueryPlan
 * (actual sqlText + EXPLAIN output) for the endpoint, an LLM call turns that into a
 * concrete example CREATE INDEX statement. If no plan is captured yet, the key pool
 * is exhausted (429), or the call fails, we fall back to the generic template — never
 * block the page on the AI call.
 *
 * HIGH_ERROR_RATE / SERVER_ERROR findings intentionally produce no recommendation:
 * there's no generic fix for "something threw an error" without knowing the actual
 * exception, so suggesting one would be a fabricated, unhelpful guess.
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
    private final GeminiKeyRotator keyRotator;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RecommendationService(DiagnosisService diagnosisService,
                                  SlowQueryPlanRepository slowQueryPlanRepository,
                                  GeminiKeyRotator keyRotator) {
        this.diagnosisService = diagnosisService;
        this.slowQueryPlanRepository = slowQueryPlanRepository;
        this.keyRotator = keyRotator;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, List<Recommendation>> getRecommendations() {
        return getRecommendations(null);
    }

    // App-selector-scoped version. Blank/null applicationName means "All Apps" —
    // same combined behavior as getRecommendations() above.
    public Map<String, List<Recommendation>> getRecommendations(String applicationName) {
        List<DiagnosisFinding> allFindings = diagnosisService.runDiagnosis(applicationName);

        Map<String, List<DiagnosisFinding>> byEndpoint = allFindings.stream()
                .collect(Collectors.groupingBy(DiagnosisFinding::getEndpoint));

        Map<String, List<Recommendation>> byEndpointRecs = new LinkedHashMap<>();
        Map<String, List<Recommendation>> raw = new LinkedHashMap<>();

        for (Map.Entry<String, List<DiagnosisFinding>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<String> ruleTypes = entry.getValue().stream()
                    .map(DiagnosisFinding::getRuleType)
                    .collect(Collectors.toList());

            List<Recommendation> recsForEndpoint = new ArrayList<>();
            for (DiagnosisFinding finding : entry.getValue()) {
                Recommendation rec = buildRecommendation(endpoint, finding, ruleTypes);
                if (rec != null) {
                    recsForEndpoint.add(rec);
                }
            }
            if (!recsForEndpoint.isEmpty()) {
                raw.put(endpoint, recsForEndpoint);
            }
        }

        raw.entrySet().stream()
                .sorted((a, b) -> worstSeverity(b.getValue()) - worstSeverity(a.getValue()))
                .forEach(e -> {
                    List<Recommendation> sorted = new ArrayList<>(e.getValue());
                    sorted.sort((a, b) -> severityRank(b.getSeverity()) - severityRank(a.getSeverity()));
                    byEndpointRecs.put(e.getKey(), sorted);
                });

        return byEndpointRecs;
    }

    private int worstSeverity(List<Recommendation> recs) {
        return recs.stream().mapToInt(r -> severityRank(r.getSeverity())).max().orElse(0);
    }

    private Recommendation buildRecommendation(String endpoint, DiagnosisFinding finding, List<String> relatedFindings) {
        switch (finding.getRuleType()) {

            case "MISSING_INDEX_CANDIDATE":
                return buildIndexRecommendation(endpoint, finding, relatedFindings);

            case "POSSIBLE_N_PLUS_ONE":
                return buildNPlusOneRecommendation(endpoint, finding, relatedFindings);

            case "SLOW_REQUEST":
                if (relatedFindings.contains("POSSIBLE_N_PLUS_ONE") || relatedFindings.contains("MISSING_INDEX_CANDIDATE")) {
                    return null;
                }
                return buildSlowRequestRecommendation(endpoint, finding, relatedFindings);

            case "HIGH_ERROR_RATE":
            case "SERVER_ERROR":
            case "ROOT_CAUSE_CORRELATION":
                return null;

            default:
                // No wrapper phrasing ("Custom rule triggered — no specific fix template
                // available yet...") — that was boilerplate identical for every custom rule
                // and added nothing the "Custom Rule" chip on the card doesn't already say.
                // Show the finding's own real message directly.
                return new Recommendation(
                        endpoint, finding.getSeverity(), finding.getRuleType(),
                        finding.getMessage(),
                        null,
                        finding.getEvidence(),
                        null, false, relatedFindings
                );
        }
    }

    private Recommendation buildNPlusOneRecommendation(String endpoint, DiagnosisFinding finding, List<String> relatedFindings) {
        Map<String, Object> ev = asMap(finding.getEvidence());
        String sampleCount = evString(ev, "sampleCount");
        String avgQueryCount = evString(ev, "averageQueryCount");
        String maxQueryCount = evString(ev, "maxQueryCount");

        String detail;
        if (sampleCount != null && avgQueryCount != null && maxQueryCount != null) {
            detail = String.format(
                    "Across %s sampled requests, this endpoint averaged %s SQL queries and spiked to a maximum " +
                    "of %s in at least one call — the classic N+1 shape. In JPA/Hibernate, fix it with a " +
                    "JOIN FETCH in the repository query, an @EntityGraph on the method, or a batch-fetch-size " +
                    "hint — whichever fits the actual relationship being loaded. VeloxDiag doesn't know the " +
                    "exact entity, so verify against the repository method backing this endpoint before applying.",
                    sampleCount, avgQueryCount, maxQueryCount);
        } else {
            detail = "This endpoint issues a growing number of near-identical queries per request, the " +
                    "classic N+1 shape. In JPA/Hibernate, fix it with a JOIN FETCH in the repository query, " +
                    "an @EntityGraph on the method, or a batch-fetch-size hint — whichever fits the actual " +
                    "relationship being loaded. VeloxDiag doesn't know the exact entity, so verify against " +
                    "the repository method backing this endpoint before applying.";
        }

        return new Recommendation(
                endpoint, finding.getSeverity(), finding.getRuleType(),
                "Batch or eager-fetch the related entity instead of querying per row",
                detail,
                finding.getEvidence(),
                "// Example — replace the per-row lookup with a single fetch join:\n" +
                "@Query(\"SELECT e FROM Entity e JOIN FETCH e.relatedEntity WHERE ...\")",
                false, relatedFindings
        );
    }

    private Recommendation buildSlowRequestRecommendation(String endpoint, DiagnosisFinding finding, List<String> relatedFindings) {
        Map<String, Object> ev = asMap(finding.getEvidence());
        String avgDuration = evString(ev, "averageDurationMs");
        String sampleCount = evString(ev, "sampleCount");

        String detail;
        if (avgDuration != null) {
            detail = String.format(
                    "This endpoint is averaging %sms per request%s, but shows no query-count spike or " +
                    "consistently-slow index pattern. The cause is likely outside the database layer: an " +
                    "external API call, serialization overhead, or synchronous work that could be made " +
                    "async. Check the Query Analyzer and Slow Queries pages for this endpoint for more " +
                    "evidence before guessing further.",
                    avgDuration, sampleCount != null ? " across " + sampleCount + " sampled requests" : "");
        } else {
            detail = "This endpoint is slow but doesn't show a query-count spike or a consistently-slow " +
                    "index pattern. The cause is likely outside the database layer: an external API call, " +
                    "serialization overhead, or synchronous work that could be made async. Check the " +
                    "Query Analyzer and Slow Queries pages for this endpoint for more evidence before guessing further.";
        }

        return new Recommendation(
                endpoint, finding.getSeverity(), finding.getRuleType(),
                "Profile this endpoint — no query-level cause identified yet",
                detail, finding.getEvidence(),
                null, false, relatedFindings
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object evidence) {
        if (evidence instanceof Map) {
            return (Map<String, Object>) evidence;
        }
        return null;
    }

    private String evString(Map<String, Object> ev, String key) {
        if (ev == null) return null;
        Object v = ev.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static final String SUGGESTION_SYSTEM_PROMPT =
            "You are a senior backend engineer giving a concrete fix suggestion inside VeloxDiag, an " +
            "agent-based application performance diagnosis platform. Context on how VeloxDiag works, so " +
            "you understand where this finding came from and what the person reading your suggestion " +
            "already knows:\n\n" +
            "- VeloxDiag instruments monitored applications with a lightweight starter library " +
            "(veloxdiag-starter) that captures real HTTP request timing and, for Java/Spring apps, SQL " +
            "query counts via a Hibernate StatementInspector. It never sends raw telemetry to you — only " +
            "already-computed findings (rule type, severity, message, and aggregate evidence like sample " +
            "counts, averages, and maximums).\n" +
            "- The rule engine that produced this finding is deterministic, not AI — it decided WHAT is " +
            "wrong using real measured thresholds (e.g. SLOW_REQUEST fires on average duration over a " +
            "configurable threshold; POSSIBLE_N_PLUS_ONE fires on maximum observed query count per " +
            "request, to catch load-dependent spikes; MISSING_INDEX_CANDIDATE only fires when an actual " +
            "captured EXPLAIN plan shows a sequential scan). Your job is narrower: given a finding that's " +
            "ALREADY been decided, explain HOW to fix it — you are not deciding whether it's a real problem.\n" +
            "- Monitored applications on this platform include Java Spring Boot services (JPA/Hibernate + " +
            "MySQL or PostgreSQL) and a Node/Express/Mongoose service backed by MongoDB. If the endpoint " +
            "path or evidence gives you no clue which stack it's on, give framework-appropriate advice for " +
            "the most likely stack based on the finding's shape, and briefly note the Mongoose/MongoDB " +
            "equivalent (e.g. .populate() or a lookup aggregation stage in place of a JOIN FETCH) as an " +
            "alternative in case it's the Node service instead.\n\n" +
            "Given the finding below (rule type, severity, message, and whatever real evidence is present), " +
            "write a detailed, endpoint-specific suggestion: 4-6 sentences plus one short illustrative code " +
            "example. Reference the actual numbers given — do not write generic boilerplate that could " +
            "apply to any endpoint. Explain not just WHAT to change but WHY it addresses this specific " +
            "evidence (e.g. why a JOIN FETCH fixes a query count that spikes under load rather than staying " +
            "flat). Do not invent an entity, table, or column name that isn't present in the input — keep " +
            "code examples generic/illustrative if the real schema isn't known. If the evidence is " +
            "borderline or inconclusive (e.g. sample size close to the minimum, or the effect size is " +
            "small), say so plainly and suggest what additional evidence (more samples, a captured query " +
            "plan, etc.) would make the fix more certain, rather than overstating confidence.";

    /**
     * On-demand, tailored version of the suggestion — mirrors NarrativeService's
     * "Explain this" pattern. Called lazily from the dashboard, not baked into
     * the default getRecommendations() response. Falls back to the existing
     * static template text (via buildRecommendation) if the key pool is empty,
     * the finding can't be found, or the call fails — never blocks the UI.
     */
    public RecommendationExplanation generateAiSuggestion(String endpoint, String ruleType) {
        List<DiagnosisFinding> findings = diagnosisService.getFindingsForEndpoint(endpoint);
        DiagnosisFinding finding = findings.stream()
                .filter(f -> f.getRuleType().equals(ruleType))
                .findFirst()
                .orElse(null);

        if (finding == null) {
            return new RecommendationExplanation(endpoint, ruleType,
                    "No active finding of this type was found for this endpoint — it may have been resolved.",
                    false);
        }

        String fallbackText;
        try {
            fallbackText = fallbackSuggestionText(endpoint, finding, findings);
        } catch (Exception e) {
            // buildRecommendation() can hit the DB (MISSING_INDEX_CANDIDATE path queries
            // SlowQueryPlanRepository) — never let that 500 the whole /explain endpoint.
            fallbackText = "No fix template available for this finding.";
        }

        if (!keyRotator.hasKeys()) {
            return new RecommendationExplanation(endpoint, ruleType, fallbackText, false);
        }

        String prompt = SUGGESTION_SYSTEM_PROMPT + "\n\n" +
                "Endpoint: " + endpoint + "\n" +
                "Rule type: " + finding.getRuleType() + "\n" +
                "Severity: " + finding.getSeverity() + "\n" +
                "Message: " + finding.getMessage() + "\n" +
                (finding.getEvidence() != null ? "Evidence: " + finding.getEvidence() + "\n" : "");

        try {
            String result = callGeminiForSuggestion(prompt);
            if (result != null && !result.isBlank()) {
                return new RecommendationExplanation(endpoint, ruleType, result, true);
            }
        } catch (Exception e) {
            // fall through to fallback text below
        }

        return new RecommendationExplanation(endpoint, ruleType, fallbackText, false);
    }

    // Reuses the existing deterministic message text as the fallback, so the
    // fallback path still shows real numbers (see buildNPlusOneRecommendation /
    // buildSlowRequestRecommendation) rather than a separate hardcoded string.
    private String fallbackSuggestionText(String endpoint, DiagnosisFinding finding, List<DiagnosisFinding> relatedFindingsRaw) {
        List<String> relatedFindings = relatedFindingsRaw.stream()
                .map(DiagnosisFinding::getRuleType)
                .collect(Collectors.toList());
        Recommendation rec = buildRecommendation(endpoint, finding, relatedFindings);
        return rec != null ? rec.getMessage() : "No fix template available for this finding.";
    }

    private String callGeminiForSuggestion(String prompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode contentEntry = contents.addObject();
        ArrayNode parts = contentEntry.putArray("parts");
        parts.addObject().put("text", prompt);
        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("maxOutputTokens", 1500);
        // Stops gemini-flash-latest's internal "thinking" tokens from eating into
        // maxOutputTokens and truncating the actual suggestion mid-sentence —
        // the real fix, instead of raising the token cap again each time this shows up.
        generationConfig.putObject("thinkingConfig").put("thinkingBudget", 0);
        String requestBody = objectMapper.writeValueAsString(root);

        int attempts = Math.max(1, keyRotator.keyCount());
        for (int i = 0; i < attempts; i++) {
            String url = String.format(API_URL_TEMPLATE, keyRotator.current());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode responseRoot = objectMapper.readTree(response.body());
                JsonNode candidates = responseRoot.path("candidates");
                if (candidates.isArray() && candidates.size() > 0) {
                    String finishReason = candidates.get(0).path("finishReason").asText("UNKNOWN");
                    System.out.println("[VeloxDiag] Gemini suggestion finishReason=" + finishReason);
                    JsonNode textParts = candidates.get(0).path("content").path("parts");
                    if (textParts.isArray() && textParts.size() > 0) {
                        String text = textParts.get(0).path("text").asText();
                        return text.isBlank() ? null : text.trim();
                    }
                }
                return null;
            }

            System.out.println("[VeloxDiag] Gemini suggestion call failed status=" + response.statusCode() + " body=" + response.body().substring(0, Math.min(200, response.body().length())));
            if (response.statusCode() == 429 && keyRotator.rotate()) {
                continue;
            }
            return null;
        }
        return null;
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

        if (seqScanPlan == null || !keyRotator.hasKeys()) {
            return new Recommendation(endpoint, finding.getSeverity(), finding.getRuleType(),
                    "Add a database index — pattern suggests a missing index",
                    genericDetail, finding.getEvidence(), genericCode, false, relatedFindings);
        }

        try {
            String aiResult = callGeminiForIndex(seqScanPlan);
            if (aiResult != null) {
                String[] parts = parseSqlWhy(aiResult);
                return new Recommendation(endpoint, finding.getSeverity(), finding.getRuleType(),
                        "Add a database index — suggested statement below",
                        parts[1] != null ? parts[1] : genericDetail,
                        finding.getEvidence(),
                        parts[0] != null ? parts[0] : genericCode,
                        parts[0] != null, relatedFindings);
            }
        } catch (Exception e) {
            // fall through to generic
        }

        return new Recommendation(endpoint, finding.getSeverity(), finding.getRuleType(),
                "Add a database index — pattern suggests a missing index",
                genericDetail, finding.getEvidence(), genericCode, false, relatedFindings);
    }

    // Tries current key; on 429 rotates and retries once per remaining key.
    // Returns null (never throws for 429) so caller falls back to template cleanly.
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
        generationConfig.putObject("thinkingConfig").put("thinkingBudget", 0);
        String requestBody = objectMapper.writeValueAsString(root);

        int attempts = Math.max(1, keyRotator.keyCount());
        for (int i = 0; i < attempts; i++) {
            String url = String.format(API_URL_TEMPLATE, keyRotator.current());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
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

            if (response.statusCode() == 429 && keyRotator.rotate()) {
                continue; // try next key
            }
            return null; // non-429 failure or no more keys — fall back to template
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