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
public class AgentReviewGateway {
    private final JdbcClient jdbc;
    private final CaseService cases;
    private final RestClient playbooks;
    public AgentReviewGateway(JdbcClient jdbc, CaseService cases, RestClient.Builder clients,
            @Value("${ih.playbook.base-url}") String url) {
        this.jdbc = jdbc; this.cases = cases; playbooks = clients.clone().baseUrl(url).build();
    }
    @Transactional
    public JsonNode apply(UUID caseId, String stepId, Object submission, String operation,
            String tenant, String actor, String correlation) {
        if (tenant.isBlank() || actor.isBlank() || submission == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        jdbc.sql("select case_id from investigation_case where case_id = :id for update")
                .param("id", caseId).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        var current = cases.get(caseId);
        if (!tenant.equals(current.tenantId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        try {
            return playbooks.post().uri("/internal/playbooks/cases/{caseId}/steps/{stepId}/" + operation, caseId, stepId)
                    .header("X-Tenant-Id", tenant).header("X-Actor-Id", actor).header("X-Correlation-Id", correlation)
                    .body(Map.of("currentCase", current, "submission", submission)).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(error.getStatusCode(), error.getResponseBodyAsString());
        }
    }
}
