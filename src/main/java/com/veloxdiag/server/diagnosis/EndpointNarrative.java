package com.veloxdiag.server.diagnosis;

import java.util.List;

public class EndpointNarrative {

    private final String endpoint;
    private final String narrative;
    private final List<String> basedOnRuleTypes;

    public EndpointNarrative(String endpoint, String narrative, List<String> basedOnRuleTypes) {
        this.endpoint = endpoint;
        this.narrative = narrative;
        this.basedOnRuleTypes = basedOnRuleTypes;
    }

    public String getEndpoint() { return endpoint; }
    public String getNarrative() { return narrative; }
    public List<String> getBasedOnRuleTypes() { return basedOnRuleTypes; }
}