package com.investigationhub.cases;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/cases")
@Tag(name = "Cases")
public class CaseController {
    private final CaseService cases;
    private final CaseActionService actions;
    private final CaseJourneyService journeys;

    public CaseController(CaseService cases, CaseActionService actions, CaseJourneyService journeys) {
        this.cases = cases;
        this.actions = actions;
        this.journeys = journeys;
    }

    @PostMapping("/ingest-alert")
    @Operation(summary = "Idempotently attach a verified alert to an eligible case")
    public CaseService.CaseView ingest(@RequestBody CaseService.IngestAlert command) {
        return cases.ingest(command);
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "Get authoritative case classification and revision")
    public CaseService.CaseView get(@PathVariable UUID caseId) {
        return cases.get(caseId);
    }

    @GetMapping("/by-alert/{alertId}")
    @Operation(summary = "Find the case containing a canonical alert")
    public CaseService.CaseView byAlert(@PathVariable UUID alertId) {
        return cases.byAlert(alertId);
    }

    @GetMapping("/{caseId}/available-actions")
    @Operation(summary = "Get policy-permitted case handoff and closure actions for a reviewer")
    public List<CaseActionService.ActionOption> availableActions(@PathVariable UUID caseId,
            @RequestHeader("X-Actor-Id") String actorId) {
        return actions.available(caseId, actorId);
    }

    @PostMapping("/{caseId}/actions")
    @Operation(summary = "Apply a policy-permitted case handoff or closure decision")
    public CaseService.CaseView action(@PathVariable UUID caseId,
            @RequestBody CaseActionService.ActionCommand command,
            @RequestHeader("X-Actor-Id") String actorId,
            @RequestHeader("X-Correlation-Id") String correlationId) {
        return actions.act(caseId, command, actorId, correlationId);
    }

    @GetMapping("/{caseId}/actions")
    @Operation(summary = "Read the auditable case-review action history")
    public List<CaseActionService.ActionHistory> actionHistory(@PathVariable UUID caseId) {
        return actions.history(caseId);
    }

    @GetMapping("/{caseId}/journey")
    @Operation(summary = "Get the pinned review-stage map and complete case event journey")
    public CaseJourneyService.Journey journey(@PathVariable UUID caseId) {
        return journeys.get(caseId);
    }
}
