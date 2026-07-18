package com.veloxdiag.server.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingsRepository extends JpaRepository<AppSettingsEntity, Long> {
}