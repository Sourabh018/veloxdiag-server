package com.veloxdiag.server.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingsRepository extends JpaRepository<AppSettingsEntity, String> {
    // findById(applicationName) now works out of the box since the PK is
    // the applicationName String — no custom query method needed.
}