package com.veloxdiag.server.diagnosis.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloxdiag.server.diagnosis.DiagnosisFinding;
import com.veloxdiag.server.entity.Telemetry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates DB-stored RuleDefinitions against computed endpoint metrics.
 * This is the actual "engine" — it doesn't know about any specific rule; it just
 * loads whatever's in rule_definitions, computes the metric vocabulary once per
 * endpoint via EndpointMetrics, and checks each rule's conditions against it.
 *
 * Runs additively alongside the existing hardcoded checks in DiagnosisService and
 * the ROOT_CAUSE_CORRELATION rule — none of that is touched or replaced.
 */
@Service
public class RuleEngineService {

    private final RuleDefinitionRepository ruleDefinitionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RuleEngineService(RuleDefinitionRepository ruleDefinitionRepository) {
        this.ruleDefinitionRepository = ruleDefinitionRepository;
    }

    public List<DiagnosisFinding> evaluate(String endpoint, List<Telemetry> records) {
        List<RuleDefinitionEntity> rules = ruleDefinitionRepository.findByEnabledTrue();
        if (rules.isEmpty()) {
            return List.of();
        }

        Map<String, Double> metrics = EndpointMetrics.compute(records);
        if (metrics.isEmpty()) {
            return List.of();
        }

        List<DiagnosisFinding> findings = new ArrayList<>();

        for (RuleDefinitionEntity rule : rules) {
            List<RuleCondition> conditions;
            try {
                conditions = objectMapper.readValue(
                        rule.getConditionsJson(),
                        new TypeReference<List<RuleCondition>>() {}
                );
            } catch (Exception e) {
                // A malformed conditionsJson for one rule (e.g. bad manual edit via
                // the API) should not break diagnosis for every other rule/endpoint —
                // skip just this rule and keep going.
                continue;
            }

            if (conditions.isEmpty()) {
                continue;
            }

            boolean allConditionsPass = conditions.stream()
                    .allMatch(c -> c.evaluate(metrics.get(c.getMetric())));

            if (allConditionsPass) {
                Map<String, Object> evidence = new HashMap<>(metrics);
                String message = renderTemplate(rule.getMessageTemplate(), endpoint, metrics);

                findings.add(new DiagnosisFinding(
                        rule.getRuleType(),
                        rule.getSeverity(),
                        endpoint,
                        message,
                        evidence
                ));
            }
        }

        return findings;
    }

    /**
     * Replaces {metricName} placeholders in a rule's messageTemplate with the
     * endpoint's actual computed values, plus a special {endpoint} placeholder.
     * Unknown placeholders are left as-is rather than throwing, so a typo in a
     * rule's template degrades gracefully instead of breaking diagnosis.
     */
    private String renderTemplate(String template, String endpoint, Map<String, Double> metrics) {
        String result = template.replace("{endpoint}", endpoint);
        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, String.format("%.1f", entry.getValue()));
            }
        }
        return result;
    }
}