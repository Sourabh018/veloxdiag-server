package com.veloxdiag.server.diagnosis;

public class MigrationScriptResult {
    private final String endpoint;
    private final String ruleType;
    private final String migrationScript;
    private final String commitMessage;
    private final boolean aiGenerated;
    private final String unavailableReason; // null when aiGenerated content is present

    public MigrationScriptResult(String endpoint, String ruleType, String migrationScript,
                                  String commitMessage, boolean aiGenerated, String unavailableReason) {
        this.endpoint = endpoint;
        this.ruleType = ruleType;
        this.migrationScript = migrationScript;
        this.commitMessage = commitMessage;
        this.aiGenerated = aiGenerated;
        this.unavailableReason = unavailableReason;
    }

    public String getEndpoint() { return endpoint; }
    public String getRuleType() { return ruleType; }
    public String getMigrationScript() { return migrationScript; }
    public String getCommitMessage() { return commitMessage; }
    public boolean isAiGenerated() { return aiGenerated; }
    public String getUnavailableReason() { return unavailableReason; }
}