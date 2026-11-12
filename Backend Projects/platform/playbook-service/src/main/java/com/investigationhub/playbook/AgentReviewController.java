package com.investigationhub.playbook;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

@RestController
public class AgentReviewController {
    private final AgentReviewService agents;
    public AgentReviewController(AgentReviewService agents) { this.agents = agents; }
    @GetMapping("/internal/playbooks/cases/{caseId}/steps/{stepId}/agent-context")
    @Operation(summary = "Internal bounded agent tool: read supplied sources and pinned step requirements")
    public AgentReviewService.Context context(@PathVariable UUID caseId, @PathVariable String stepId,
            @RequestHeader("X-Tenant-Id") String tenant) { return agents.context(caseId, stepId, tenant); }
    @GetMapping("/v1/playbooks/cases/{caseId}/steps/{stepId}/agent-findings")
    @Operation(summary = "Read agent findings and their human review state")
    public List<AgentReviewService.SubmissionView> list(@PathVariable UUID caseId, @PathVariable String stepId,
            @RequestHeader("X-Tenant-Id") String tenant) { return agents.list(caseId, stepId, tenant); }
    @PostMapping("/internal/playbooks/cases/{caseId}/steps/{stepId}/agent-findings")
    public AgentReviewService.SubmissionView submit(@PathVariable UUID caseId, @PathVariable String stepId,
            @RequestBody AgentReviewService.ProposalEnvelope body, @RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-Actor-Id") String actor, @RequestHeader("X-Correlation-Id") String correlation) {
        return agents.submit(caseId, stepId, body, tenant, actor, correlation);
    }
    @PostMapping("/internal/playbooks/cases/{caseId}/steps/{stepId}/agent-review")
    public AgentReviewService.ReviewReceipt review(@PathVariable UUID caseId, @PathVariable String stepId,
            @RequestBody AgentReviewService.ReviewEnvelope body, @RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-Actor-Id") String actor, @RequestHeader("X-Correlation-Id") String correlation) {
        return agents.review(caseId, stepId, body, tenant, actor, correlation);
    }
}
