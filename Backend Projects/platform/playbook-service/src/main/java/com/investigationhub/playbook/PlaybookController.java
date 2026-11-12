package com.investigationhub.playbook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/playbooks/cases")
@Tag(name = "Playbooks")
public class PlaybookController {
    private final PlaybookService playbooks;
    private final StepNavigationService navigation;

    public PlaybookController(PlaybookService playbooks, StepNavigationService navigation) {
        this.playbooks = playbooks;
        this.navigation = navigation;
    }

    @GetMapping("/{caseId}/navigation")
    @Operation(summary = "Resolve navigation for selected steps using the pinned policy")
    public List<StepNavigationService.Navigation> navigation(@PathVariable UUID caseId) {
        return navigation.list(caseId);
    }

    @GetMapping("/{caseId}/steps/{stepId}/navigation")
    @Operation(summary = "Resolve a selected step's section and case context")
    public StepNavigationService.Navigation stepNavigation(@PathVariable UUID caseId,
            @PathVariable String stepId) {
        return navigation.get(caseId, stepId);
    }

    @PostMapping("/{caseId}/reconcile")
    @Operation(summary = "Idempotently reconcile an authoritative case revision into Flowable")
    public PlaybookService.PlaybookView reconcile(@PathVariable UUID caseId,
            @RequestBody PlaybookService.CaseSnapshot snapshot,
            @RequestHeader("X-Event-Id") UUID eventId,
            @RequestHeader("X-Correlation-Id") String correlationId) {
        return playbooks.reconcile(caseId, snapshot, eventId, correlationId);
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "Get Flowable execution state for a product case")
    public PlaybookService.PlaybookView get(@PathVariable UUID caseId) {
        return playbooks.get(caseId);
    }

    @GetMapping("/{caseId}/history")
    @Operation(summary = "Get retained Flowable plan-item history for a product case")
    public List<PlaybookService.StepView> history(@PathVariable UUID caseId) {
        return playbooks.history(caseId);
    }

    @GetMapping("/{caseId}/readiness")
    @Operation(summary = "Check whether all policy-required playbook steps have completed")
    public PlaybookService.Readiness readiness(@PathVariable UUID caseId) {
        return playbooks.readiness(caseId);
    }

    @PostMapping("/{caseId}/close")
    @Operation(summary = "Idempotently close a playbook after authoritative case approval")
    public PlaybookService.PlaybookView close(@PathVariable UUID caseId,
            @RequestBody PlaybookService.CloseCommand command,
            @RequestHeader("X-Correlation-Id") String correlationId) {
        return playbooks.close(caseId, command, correlationId);
    }
}
