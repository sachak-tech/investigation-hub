package com.investigationhub.playbook;

import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

@RestController
public class TaskCompletionController {
    private final TaskCompletionService completions;
    public TaskCompletionController(TaskCompletionService completions) { this.completions = completions; }

    @PostMapping("/v1/playbooks/cases/{caseId}/documents")
    @Operation(summary = "Register immutable supplied text as an evidence source")
    public TaskCompletionService.DocumentView register(@PathVariable UUID caseId,
            @RequestBody TaskCompletionService.DocumentCommand document,
            @RequestHeader("X-Tenant-Id") String tenant, @RequestHeader("X-Actor-Id") String actor) {
        return completions.register(caseId, document, tenant, actor);
    }
    @GetMapping("/v1/playbooks/cases/{caseId}/documents")
    @Operation(summary = "List registered evidence source metadata")
    public List<TaskCompletionService.DocumentView> documents(@PathVariable UUID caseId,
            @RequestHeader("X-Tenant-Id") String tenant) { return completions.documents(caseId, tenant); }

    @GetMapping("/v1/playbooks/cases/{caseId}/completions")
    @Operation(summary = "Read findings, verified citations and completion audit receipts")
    public List<TaskCompletionService.Receipt> history(@PathVariable UUID caseId,
            @RequestHeader("X-Tenant-Id") String tenant) { return completions.history(caseId, tenant); }

    @PostMapping("/internal/playbooks/cases/{caseId}/steps/{stepId}/complete")
    @Operation(summary = "Internal completion transaction; invoke through Case to serialize against case changes")
    public TaskCompletionService.Receipt complete(@PathVariable UUID caseId, @PathVariable String stepId,
            @RequestBody TaskCompletionService.Envelope envelope,
            @RequestHeader("X-Tenant-Id") String tenant, @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Correlation-Id") String correlation) {
        return completions.complete(caseId, stepId, envelope, tenant, actor, correlation);
    }
}
