package com.investigationhub.cases;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/cases/{caseId}/steps/{stepId}")
public class AgentReviewController {
    private final AgentReviewGateway gateway;
    public AgentReviewController(AgentReviewGateway gateway) { this.gateway = gateway; }
    @PostMapping("/agent-findings")
    @Operation(summary = "Store cited agent findings without completing the human review task")
    public JsonNode submit(@PathVariable UUID caseId, @PathVariable String stepId, @RequestBody JsonNode body,
            @RequestHeader("X-Tenant-Id") String tenant, @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Correlation-Id") String correlation) {
        return gateway.apply(caseId, stepId, body, "agent-findings", tenant, actor, correlation);
    }
    @PostMapping("/agent-review")
    @Operation(summary = "Accept agent findings and complete the task, or return them for rework")
    public JsonNode review(@PathVariable UUID caseId, @PathVariable String stepId, @RequestBody Review body,
            @RequestHeader("X-Tenant-Id") String tenant, @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Correlation-Id") String correlation) {
        return gateway.apply(caseId, stepId, body, "agent-review", tenant, actor, correlation);
    }
    public record Review(UUID commandId, UUID runId, String action, String reason,
            long expectedCaseRevision, long expectedPlaybookRevision) {}
}
