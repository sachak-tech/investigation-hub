package com.investigationhub.playbook;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Same-origin development UI adapter; review requests always pass through the Case lock. */
@RestController
@RequestMapping("/v1/playbooks/cases/{caseId}/steps/{stepId}")
public class AgentUiController {
    private final RestClient agents;
    private final RestClient cases;
    public AgentUiController(RestClient.Builder clients, @Value("${ih.agent.base-url}") String agentUrl,
            @Value("${ih.case.base-url}") String caseUrl) {
        agents = clients.clone().baseUrl(agentUrl).build(); cases = clients.clone().baseUrl(caseUrl).build();
    }
    @PostMapping("/agent-runs")
    public JsonNode start(@PathVariable UUID caseId, @PathVariable String stepId, @RequestBody Start command,
            @RequestHeader("X-Tenant-Id") String tenant, @RequestHeader("X-Actor-Id") String actor) {
        if (command == null || command.runId() == null || command.expectedCaseRevision() < 1 || command.expectedPlaybookRevision() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Run ID and current revisions are required");
        }
        try {
            return agents.post().uri("/v1/agent-runs").header("X-Tenant-Id", tenant).header("X-Actor-Id", actor)
                    .body(Map.of("runId", command.runId(), "caseId", caseId, "stepId", stepId,
                            "expectedCaseRevision", command.expectedCaseRevision(), "expectedPlaybookRevision", command.expectedPlaybookRevision()))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException error) { throw new ResponseStatusException(error.getStatusCode(), error.getResponseBodyAsString()); }
    }
    @GetMapping("/agent-runs/{runId}")
    public JsonNode status(@PathVariable UUID caseId, @PathVariable String stepId, @PathVariable UUID runId,
            @RequestHeader("X-Tenant-Id") String tenant) {
        try {
            JsonNode run = agents.get().uri("/v1/agent-runs/{id}", runId).header("X-Tenant-Id", tenant).retrieve().body(JsonNode.class);
            if (run == null || !caseId.toString().equals(run.path("caseId").asText()) || !stepId.equals(run.path("stepId").asText())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run not found");
            }
            return run;
        } catch (RestClientResponseException error) { throw new ResponseStatusException(error.getStatusCode(), error.getResponseBodyAsString()); }
    }
    @PostMapping("/agent-review")
    public JsonNode review(@PathVariable UUID caseId, @PathVariable String stepId,
            @RequestBody AgentReviewService.Review command, @RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-Actor-Id") String actor) {
        if (command == null || command.commandId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review command ID is required");
        try {
            return cases.post().uri("/v1/cases/{id}/steps/{step}/agent-review", caseId, stepId)
                    .header("X-Tenant-Id", tenant).header("X-Actor-Id", actor).header("X-Correlation-Id", command.commandId().toString())
                    .body(command).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException error) { throw new ResponseStatusException(error.getStatusCode(), error.getResponseBodyAsString()); }
    }
    public record Start(UUID runId, long expectedCaseRevision, long expectedPlaybookRevision) {}
}
