package com.veloxdiag.server.diagnosis.engine;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * CRUD API for rule_definitions. This is the actual point of the rule engine:
 * POSTing a new rule here makes it live on the next /api/diagnosis call, with
 * no code change and no redeploy — e.g.:
 *
 * POST /api/rules
 * {
 *   "ruleType": "SLOW_AND_ERROR_PRONE",
 *   "severity": "HIGH",
 *   "conditionsJson": "[{\"metric\":\"avgDurationMs\",\"operator\":\"GT\",\"threshold\":2000},
 *                        {\"metric\":\"errorRate\",\"operator\":\"GTE\",\"threshold\":0.05}]",
 *   "messageTemplate": "Endpoint {endpoint} is both slow ({avgDurationMs}ms avg) and error-prone ({errorRate} error rate).",
 *   "enabled": true
 * }
 */
@RestController
@RequestMapping("/api/rules")
public class RuleDefinitionController {

    private final RuleDefinitionRepository ruleDefinitionRepository;

    public RuleDefinitionController(RuleDefinitionRepository ruleDefinitionRepository) {
        this.ruleDefinitionRepository = ruleDefinitionRepository;
    }

    @GetMapping
    public List<RuleDefinitionEntity> getAllRules() {
        return ruleDefinitionRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RuleDefinitionEntity> getRule(@PathVariable Long id) {
        Optional<RuleDefinitionEntity> rule = ruleDefinitionRepository.findById(id);
        return rule.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RuleDefinitionEntity> createRule(@RequestBody RuleDefinitionEntity rule) {
        // id is auto-generated — ignore any id the client sends to avoid overwriting
        // an existing row by accident.
        rule.setId(null);
        RuleDefinitionEntity saved = ruleDefinitionRepository.save(rule);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RuleDefinitionEntity> updateRule(@PathVariable Long id, @RequestBody RuleDefinitionEntity updates) {
        return ruleDefinitionRepository.findById(id)
                .map(existing -> {
                    existing.setRuleType(updates.getRuleType());
                    existing.setSeverity(updates.getSeverity());
                    existing.setConditionsJson(updates.getConditionsJson());
                    existing.setMessageTemplate(updates.getMessageTemplate());
                    existing.setEnabled(updates.isEnabled());
                    RuleDefinitionEntity saved = ruleDefinitionRepository.save(existing);
                    return ResponseEntity.ok(saved);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        if (!ruleDefinitionRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        ruleDefinitionRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}