package com.veloxdiag.server.service;

import org.springframework.stereotype.Service;
import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import java.util.List;

@Service
public class TelemetryService {

    private final TelemetryRepository repository;

    public TelemetryService(TelemetryRepository repository) {
        this.repository = repository;
    }
    
    public Telemetry saveTelemetry(Telemetry telemetry) {
        return repository.save(telemetry);
    }
    
    public List<Telemetry> getAllTelemetry() {
        return repository.findAll();
    }
}