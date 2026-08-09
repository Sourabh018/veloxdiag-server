package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DataGrowthController {

    private final DataGrowthService dataGrowthService;

    public DataGrowthController(DataGrowthService dataGrowthService) {
        this.dataGrowthService = dataGrowthService;
    }

    @GetMapping("/api/diagnosis/growth")
    public List<TableGrowthTrend> getGrowthTrends(@RequestParam String endpoint) {
        return dataGrowthService.getGrowthTrends(endpoint);
    }
}