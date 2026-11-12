package com.investigationhub.cases;

import java.util.UUID;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/cases")
public class StepCompletionController {
    private final StepCompletionGateway completions;
    public StepCompletionController(StepCompletionGateway completions) { this.completions = completions; }

    @PostMapping("/{caseId}/steps/{stepId}/complete")
    @Operation(summary = "Submit findings and citations to complete an active investigation task")
    public JsonNode complete(@PathVariable UUID caseId, @PathVariable String stepId,
            @RequestBody Submission submission, @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Actor-Id") String actorId,
            @RequestHeader("X-Correlation-Id") String correlationId) {
        return completions.complete(caseId, stepId, submission, tenantId, actorId, correlationId);
    }

    public record Citation(String evidenceType, UUID documentId, int startLine, int endLine, String excerpt) {}
    public record Submission(UUID commandId, long expectedCaseRevision, long expectedPlaybookRevision,
            String taskId, String findings, List<Citation> citations) {}
}
