package com.veloxdiag.server.diagnosis.engine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleDefinitionRepository extends JpaRepository<RuleDefinitionEntity, Long> {

    List<RuleDefinitionEntity> findByEnabledTrue();
}