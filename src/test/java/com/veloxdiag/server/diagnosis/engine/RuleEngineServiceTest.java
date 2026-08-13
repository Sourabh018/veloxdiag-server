package com.veloxdiag.server.diagnosis.engine;

import com.veloxdiag.server.diagnosis.DiagnosisFinding;
import com.veloxdiag.server.entity.Telemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for RuleEngineService — the part of the Rule Engine that actually
 * evaluates DB-stored rules against live telemetry. RuleDefinitionRepository
 * is mocked so these tests never touch a real database; they only check the
 * engine's own evaluation/rendering logic.
 */
class RuleEngineServiceTest {

    private RuleDefinitionRepository repository;
    private RuleEngineService engine;

    private static final String ENDPOINT = "/api/exams/{id}";

    @BeforeEach
    void setUp() {
        repository = mock(RuleDefinitionRepository.class);
        engine = new RuleEngineService(repository);
    }

    private Telemetry telemetry(long durationMs, int status, Long queryCount) {
        Telemetry t = new Telemetry("CET_CELL", ENDPOINT, "GET", status, durationMs, LocalDateTime.now());
        t.setQueryCount(queryCount);
        return t;
    }

    private RuleDefinitionEntity rule(String type, String severity, String conditionsJson,
                                       String template, boolean enabled) {
        return new RuleDefinitionEntity(type, severity, conditionsJson, template, enabled);
    }

    @Test
    @DisplayName("returns no findings when there are no enabled rules")
    void noFindingsWithNoRules() {
        List<Telemetry> records = List.of(telemetry(2000, 200, 5L));
        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records, List.of());
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("fires a rule whose single condition is satisfied")
    void firesWhenConditionMet() {
        RuleDefinitionEntity r = rule(
                "SLOW_CUSTOM",
                "HIGH",
                "[{\"metric\":\"avgDurationMs\",\"operator\":\"GT\",\"threshold\":1000}]",
                "Endpoint {endpoint} is averaging {avgDurationMs}ms",
                true);

        List<Telemetry> records = List.of(
                telemetry(2000, 200, 5L),
                telemetry(2200, 200, 5L)
        );

        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records, List.of(r));

        assertThat(findings).hasSize(1);
        DiagnosisFinding finding = findings.get(0);
        assertThat(finding.getRuleType()).isEqualTo("SLOW_CUSTOM");
        assertThat(finding.getSeverity()).isEqualTo("HIGH");
        assertThat(finding.getMessage()).contains(ENDPOINT).contains("2100.0ms"); // avg of 2000/2200
    }

    @Test
    @DisplayName("does not fire when the condition is not met")
    void doesNotFireWhenConditionNotMet() {
        RuleDefinitionEntity r = rule(
                "SLOW_CUSTOM",
                "HIGH",
                "[{\"metric\":\"avgDurationMs\",\"operator\":\"GT\",\"threshold\":5000}]",
                "Endpoint {endpoint} is averaging {avgDurationMs}ms",
                true);

        List<Telemetry> records = List.of(telemetry(200, 200, 5L));
        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records, List.of(r));
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("requires ALL conditions to pass (AND), not just one")
    void requiresAllConditionsToPass() {
        // duration is high enough, but error rate is not -> should NOT fire
        RuleDefinitionEntity r = rule(
                "SLOW_AND_ERROR_PRONE",
                "HIGH",
                "[{\"metric\":\"avgDurationMs\",\"operator\":\"GT\",\"threshold\":1000}," +
                        "{\"metric\":\"errorRate\",\"operator\":\"GTE\",\"threshold\":0.5}]",
                "Endpoint {endpoint} is slow and error-prone",
                true);

        List<Telemetry> records = List.of(
                telemetry(2000, 200, 5L),
                telemetry(2000, 200, 5L)
        ); // both 200s -> errorRate = 0.0, fails second condition

        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records, List.of(r));
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("fires when ALL conditions pass together")
    void firesWhenAllConditionsPass() {
        RuleDefinitionEntity r = rule(
                "SLOW_AND_ERROR_PRONE",
                "HIGH",
                "[{\"metric\":\"avgDurationMs\",\"operator\":\"GT\",\"threshold\":1000}," +
                        "{\"metric\":\"errorRate\",\"operator\":\"GTE\",\"threshold\":0.5}]",
                "Endpoint {endpoint} is slow and error-prone",
                true);

        List<Telemetry> records = List.of(
                telemetry(2000, 500, 5L),
                telemetry(2000, 200, 5L)
        ); // errorRate = 0.5, avgDuration = 2000 -> both pass

        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records, List.of(r));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getRuleType()).isEqualTo("SLOW_AND_ERROR_PRONE");
    }

    @Test
    @DisplayName("skips a rule with malformed conditionsJson instead of throwing, and still evaluates the rest")
    void skipsMalformedRuleWithoutBreakingOthers() {
        RuleDefinitionEntity broken = rule(
                "BROKEN_RULE", "HIGH", "not valid json {{{", "should never fire", true);
        RuleDefinitionEntity valid = rule(
                "VALID_RULE", "MEDIUM",
                "[{\"metric\":\"avgDurationMs\",\"operator\":\"GT\",\"threshold\":100}]",
                "Endpoint {endpoint} slow", true);

        List<Telemetry> records = List.of(telemetry(2000, 200, 5L));
        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records, List.of(broken, valid));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getRuleType()).isEqualTo("VALID_RULE");
    }

    @Test
    @DisplayName("skips a rule with an empty conditions array")
    void skipsRuleWithEmptyConditions() {
        RuleDefinitionEntity r = rule("EMPTY_RULE", "HIGH", "[]", "never fires", true);
        List<Telemetry> records = List.of(telemetry(2000, 200, 5L));
        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records, List.of(r));
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("a rule referencing an unknown metric name never fires, does not throw")
    void unknownMetricNeverFiresWithoutThrowing() {
        RuleDefinitionEntity r = rule(
                "TYPO_RULE", "HIGH",
                "[{\"metric\":\"avgDuratoinMs\",\"operator\":\"GT\",\"threshold\":100}]", // typo'd metric name
                "should never fire", true);

        List<Telemetry> records = List.of(telemetry(2000, 200, 5L));
        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records, List.of(r));
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("returns no findings for an endpoint with no telemetry records at all")
    void noFindingsWithEmptyRecords() {
        RuleDefinitionEntity r = rule(
                "SLOW_CUSTOM", "HIGH",
                "[{\"metric\":\"avgDurationMs\",\"operator\":\"GT\",\"threshold\":100}]",
                "should never fire on no data", true);

        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, List.of(), List.of(r));
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("evaluate(endpoint, records) convenience overload loads enabled rules from the repository")
    void convenienceOverloadLoadsFromRepository() {
        RuleDefinitionEntity r = rule(
                "SLOW_CUSTOM", "HIGH",
                "[{\"metric\":\"avgDurationMs\",\"operator\":\"GT\",\"threshold\":100}]",
                "Endpoint {endpoint} slow", true);
        when(repository.findByEnabledTrue()).thenReturn(List.of(r));

        List<Telemetry> records = List.of(telemetry(2000, 200, 5L));
        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getRuleType()).isEqualTo("SLOW_CUSTOM");
    }

    @Test
    @DisplayName("leaves an unknown/typo'd placeholder in the message template untouched rather than throwing")
    void unknownPlaceholderLeftAsIs() {
        RuleDefinitionEntity r = rule(
                "SLOW_CUSTOM", "HIGH",
                "[{\"metric\":\"avgDurationMs\",\"operator\":\"GT\",\"threshold\":100}]",
                "Duration is {avgDurationMs}ms, unknown is {notARealMetric}", true);

        List<Telemetry> records = List.of(telemetry(2000, 200, 5L));
        List<DiagnosisFinding> findings = engine.evaluate(ENDPOINT, records, List.of(r));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getMessage())
                .contains("2000.0ms")
                .contains("{notARealMetric}"); // left untouched, not replaced with garbage
    }
}