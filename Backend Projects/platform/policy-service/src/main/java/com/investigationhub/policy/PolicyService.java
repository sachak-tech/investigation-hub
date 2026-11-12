package com.investigationhub.policy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PolicyService {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public PolicyService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Bean
    ApplicationRunner seedSyntheticPolicy() {
        return args -> {
            for (String resource : List.of("synthetic-policy.json", "expanded-aml-policy.json", "sanctions-policy.json")) {
                String document;
                try (var input = new ClassPathResource("policies/" + resource).getInputStream()) {
                    document = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                JsonNode policy = json.readTree(document);
                jdbc.sql("""
                        insert into policy_definition (tenant_id, policy_id, version, status, document, published_at)
                        values (:tenantId, :policyId, :version, 'PUBLISHED', cast(:document as jsonb), now())
                        on conflict (tenant_id, policy_id, version) do nothing
                        """)
                        .param("tenantId", policy.path("tenantId").asText())
                        .param("policyId", policy.path("policyId").asText())
                        .param("version", policy.path("version").asInt())
                        .param("document", document).update();
            }
        };
    }

    public JsonNode active(String tenantId) {
        String document = jdbc.sql("""
                select document::text from policy_definition
                where tenant_id = :tenantId and status = 'PUBLISHED'
                """).param("tenantId", tenantId).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No published policy"));
        return json.readTree(document);
    }

    public JsonNode version(String tenantId, String policyId, int version) {
        String document = jdbc.sql("""
                select document::text from policy_definition
                where tenant_id = :tenantId and policy_id = :policyId and version = :version
                """)
                .param("tenantId", tenantId).param("policyId", policyId).param("version", version)
                .query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy version not found"));
        return json.readTree(document);
    }

    public Evaluation evaluate(EvaluateCaseRequest request) {
        if (request.tenantId() == null || request.alertTypes() == null || request.alertTypes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId and alertTypes are required");
        }
        JsonNode policy = active(request.tenantId());
        Set<String> alerts = new HashSet<>(request.alertTypes());
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode type : policy.path("caseTypes")) {
            if (containsAll(alerts, type.path("requiredAlertTypes"))) matches.add(type);
        }
        JsonNode chosen = matches.stream()
                .max(Comparator.comparingInt((JsonNode type) -> type.path("priority").asInt())
                        .thenComparing(type -> type.path("id").asText()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No case type matches the alert combination"));
        String caseType = chosen.path("id").asText();
        List<String> steps = new ArrayList<>();
        for (JsonNode step : policy.path("steps")) {
            JsonNode activation = step.path("activation");
            if (containsAll(alerts, activation.path("allAlertTypes"))
                    && (activation.path("anyAlertTypes").isEmpty()
                            || containsAny(alerts, activation.path("anyAlertTypes")))
                    && (activation.path("caseTypes").isEmpty()
                            || contains(activation.path("caseTypes"), caseType))) {
                steps.add(step.path("id").asText());
            }
        }
        return new Evaluation(policy.path("policyId").asText(), policy.path("version").asInt(),
                caseType, policy.path("caseGrouping").path("joinWindowDays").asInt(), steps,
                policy.path("reviewStages").get(0).path("id").asText());
    }

    public Grouping grouping(String tenantId) {
        JsonNode policy = active(tenantId);
        return new Grouping(policy.path("policyId").asText(), policy.path("version").asInt(),
                policy.path("caseGrouping").path("joinWindowDays").asInt());
    }

    private static boolean containsAll(Set<String> values, JsonNode required) {
        for (JsonNode item : required) if (!values.contains(item.asText())) return false;
        return true;
    }

    private static boolean containsAny(Set<String> values, JsonNode options) {
        for (JsonNode item : options) if (values.contains(item.asText())) return true;
        return false;
    }

    private static boolean contains(JsonNode options, String value) {
        for (JsonNode item : options) if (item.asText().equals(value)) return true;
        return false;
    }

    public record EvaluateCaseRequest(String tenantId, List<String> alertTypes) {}
    public record Evaluation(String policyId, int policyVersion, String caseType,
            int joinWindowDays, List<String> requiredStepIds, String initialReviewStage) {}
    public record Grouping(String policyId, int policyVersion, int joinWindowDays) {}
}
