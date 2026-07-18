package com.veloxdiag.server.diagnosis.engine;

import jakarta.persistence.*;

/**
 * A single diagnosis rule, stored in the DB rather than hardcoded in Java.
 * This is what makes the rule engine "data-driven": adding a new rule means
 * inserting a row (via RuleDefinitionController) — no code change, no redeploy.
 *
 * conditionsJson holds a JSON array like:
 *   [{"metric":"avgDurationMs","operator":"GT","threshold":2000},
 *    {"metric":"errorRate","operator":"GTE","threshold":0.05}]
 * All conditions must pass (AND) for the rule to fire on a given endpoint.
 *
 * messageTemplate supports {metricName} placeholders, substituted with the
 * endpoint's actual computed value at evaluation time, e.g.:
 *   "Endpoint is averaging {avgDurationMs}ms with a {errorRate} error rate."
 */
@Entity
@Table(name = "rule_definitions")
public class RuleDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String ruleType;

    @Column(nullable = false)
    private String severity; // "HIGH", "MEDIUM", "LOW"

    @Lob
    @Column(nullable = false)
    private String conditionsJson;

    @Lob
    @Column(nullable = false)
    private String messageTemplate;

    @Column(nullable = false)
    private boolean enabled = true;

    public RuleDefinitionEntity() {
        // required by JPA
    }

    public RuleDefinitionEntity(String ruleType, String severity, String conditionsJson,
                                 String messageTemplate, boolean enabled) {
        this.ruleType = ruleType;
        this.severity = severity;
        this.conditionsJson = conditionsJson;
        this.messageTemplate = messageTemplate;
        this.enabled = enabled;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String conditionsJson) { this.conditionsJson = conditionsJson; }

    public String getMessageTemplate() { return messageTemplate; }
    public void setMessageTemplate(String messageTemplate) { this.messageTemplate = messageTemplate; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}