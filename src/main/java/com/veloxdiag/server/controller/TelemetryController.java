package com.veloxdiag.server.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.service.TelemetryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @PostMapping
    public Telemetry saveTelemetry(@Valid @RequestBody Telemetry telemetry) {
        return telemetryService.saveTelemetry(telemetry);
    }

    @GetMapping
    public List<Telemetry> getAllTelemetry() {
        return telemetryService.getAllTelemetry();
    }
}