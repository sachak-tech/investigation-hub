package com.investigationhub.cases;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class StepCompletionGateway {
    private final JdbcClient jdbc;
    private final CaseService cases;
    private final RestClient playbooks;

    public StepCompletionGateway(JdbcClient jdbc, CaseService cases, RestClient.Builder clients,
            @Value("${ih.playbook.base-url}") String playbookUrl) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.playbooks = clients.clone().baseUrl(playbookUrl).build();
    }

    @Transactional
    public JsonNode complete(UUID caseId, String stepId, StepCompletionController.Submission submission, String tenantId,
            String actorId, String correlationId) {
        if (actorId.isBlank() || tenantId.isBlank() || submission == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant, actor and submission are required");
        }
        jdbc.sql("select case_id from investigation_case where case_id = :id for update")
                .param("id", caseId).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        CaseService.CaseView current = cases.get(caseId);
        if (!current.tenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        }
        try {
            return playbooks.post().uri("/internal/playbooks/cases/{caseId}/steps/{stepId}/complete", caseId, stepId)
                    .header("X-Actor-Id", actorId).header("X-Tenant-Id", tenantId)
                    .header("X-Correlation-Id", correlationId)
                    .body(Map.of("currentCase", current, "submission", submission))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(error.getStatusCode(), error.getResponseBodyAsString());
        }
    }
}
