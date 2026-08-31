package com.veloxdiag.server.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FixSnapshotRepository extends JpaRepository<FixSnapshot, Long> {

    List<FixSnapshot> findByApplicationNameOrderByMarkedFixedAtDesc(String applicationName);

    List<FixSnapshot> findAllByOrderByMarkedFixedAtDesc();
    Optional<FixSnapshot> findFirstByApplicationNameAndEndpointAndRuleTypeOrderByMarkedFixedAtDesc(
            String applicationName, String endpoint, String ruleType);

    Optional<FixSnapshot> findFirstByEndpointAndRuleTypeOrderByMarkedFixedAtDesc(String endpoint, String ruleType);

    // No admin/reset path ever deleted FixSnapshot rows (confirmed — checked
    // AdminService, it only wipes Telemetry/SlowQueryPlan/Jvm/ConnectionPool),
    // so an accidental "Mark as Fixed" click had no way to be undone even
    // after the Regression Watch auto-reopened it — the row just sat there
    // forever as a harmless but permanent "reopened" banner. This lets the
    // owner actually delete it.
    long deleteByApplicationNameAndEndpointAndRuleType(String applicationName, String endpoint, String ruleType);
}