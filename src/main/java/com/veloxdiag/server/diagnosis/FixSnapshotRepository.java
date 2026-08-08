package com.veloxdiag.server.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FixSnapshotRepository extends JpaRepository<FixSnapshot, Long> {

    List<FixSnapshot> findByApplicationNameOrderByMarkedFixedAtDesc(String applicationName);

    List<FixSnapshot> findAllByOrderByMarkedFixedAtDesc();
}