package com.veloxdiag.server.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EndpointBusinessContextRepository extends JpaRepository<EndpointBusinessContext, Long> {

    Optional<EndpointBusinessContext> findByApplicationNameAndEndpoint(String applicationName, String endpoint);

    List<EndpointBusinessContext> findByApplicationName(String applicationName);

    void deleteByApplicationNameAndEndpoint(String applicationName, String endpoint);
}