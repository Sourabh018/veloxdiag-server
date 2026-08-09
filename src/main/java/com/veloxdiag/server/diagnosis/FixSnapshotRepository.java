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
}