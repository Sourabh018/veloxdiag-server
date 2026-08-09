package com.veloxdiag.server.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
    Optional<Application> findByName(String name);
    Optional<Application> findByIngestApiKey(String ingestApiKey);
    List<Application> findByOwnerUserId(Long ownerUserId);
}