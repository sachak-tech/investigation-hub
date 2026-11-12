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
import org.springframework.transaction.annotation.Transactional;
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
                order by version desc limit 1
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
        return evaluateDocument(active(request.tenantId()), request.alertTypes());
    }

    public Evaluation simulate(SimulationRequest request) {
        if (request == null || request.document() == null || request.alertTypes() == null
                || request.alertTypes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Policy document and alert types are required");
        }
        validate(request.document());
        return evaluateDocument(request.document(), request.alertTypes());
    }

    private Evaluation evaluateDocument(JsonNode policy, List<String> alertTypes) {
        Set<String> alerts = new HashSet<>(alertTypes);
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
        List<String> optionalSteps = new ArrayList<>();
        for (JsonNode step : policy.path("steps")) {
            JsonNode activation = step.path("activation");
            if (containsAll(alerts, activation.path("allAlertTypes"))
                    && (activation.path("anyAlertTypes").isEmpty()
                            || containsAny(alerts, activation.path("anyAlertTypes")))
                    && (activation.path("caseTypes").isEmpty()
                            || contains(activation.path("caseTypes"), caseType))) {
                (step.path("required").asBoolean(true) ? steps : optionalSteps).add(step.path("id").asText());
            }
        }
        return new Evaluation(policy.path("policyId").asText(), policy.path("version").asInt(),
                caseType, policy.path("caseGrouping").path("joinWindowDays").asInt(), steps, optionalSteps,
                policy.path("reviewStages").get(0).path("id").asText());
    }

    public Grouping grouping(String tenantId) {
        JsonNode policy = active(tenantId);
        return new Grouping(policy.path("policyId").asText(), policy.path("version").asInt(),
                policy.path("caseGrouping").path("joinWindowDays").asInt());
    }

    @Transactional
    public DraftView saveDraft(JsonNode document) {
        validate(document);
        String tenantId = document.path("tenantId").asText();
        String policyId = document.path("policyId").asText();
        int version = document.path("version").asInt();
        Integer latest = jdbc.sql("""
                select max(version) from policy_definition where tenant_id = :tenantId and policy_id = :policyId
                """).param("tenantId", tenantId).param("policyId", policyId)
                .query(Integer.class).optional().orElse(null);
        if (latest != null && version <= latest) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft version must exceed existing versions");
        }
        jdbc.sql("""
                insert into policy_definition (tenant_id, policy_id, version, status, document)
                values (:tenantId, :policyId, :version, 'DRAFT', cast(:document as jsonb))
                """).param("tenantId", tenantId).param("policyId", policyId).param("version", version)
                .param("document", json.writeValueAsString(document)).update();
        return new DraftView(tenantId, policyId, version, "DRAFT");
    }

    @Transactional
    public DraftView publish(String tenantId, String policyId, int version) {
        String raw = jdbc.sql("""
                select document::text from policy_definition
                where tenant_id = :tenantId and policy_id = :policyId and version = :version and status = 'DRAFT'
                for update
                """).param("tenantId", tenantId).param("policyId", policyId).param("version", version)
                .query(String.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Draft policy version not found"));
        validate(json.readTree(raw));
        jdbc.sql("update policy_definition set status = 'RETIRED' where tenant_id = :tenantId and status = 'PUBLISHED'")
                .param("tenantId", tenantId).update();
        jdbc.sql("""
                update policy_definition set status = 'PUBLISHED', published_at = now()
                where tenant_id = :tenantId and policy_id = :policyId and version = :version
                """).param("tenantId", tenantId).param("policyId", policyId).param("version", version).update();
        return new DraftView(tenantId, policyId, version, "PUBLISHED");
    }

    private void validate(JsonNode policy) {
        if (policy == null || !policy.isObject() || !identifier(policy.path("tenantId").asText())
                || !identifier(policy.path("policyId").asText()) || policy.path("version").asInt() < 1
                || policy.path("caseGrouping").path("joinWindowDays").asInt() < 1
                || !policy.path("caseTypes").isArray() || policy.path("caseTypes").isEmpty()
                || !policy.path("reviewStages").isArray() || policy.path("reviewStages").isEmpty()
                || !policy.path("steps").isArray()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Incomplete policy document");
        }
        Set<String> caseTypes = new HashSet<>();
        for (JsonNode type : policy.path("caseTypes")) {
            if (!identifier(type.path("id").asText()) || !caseTypes.add(type.path("id").asText())
                    || !type.path("requiredAlertTypes").isArray()
                    || type.path("requiredAlertTypes").isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid case type");
            }
        }
        Set<String> stages = new HashSet<>();
        for (JsonNode stage : policy.path("reviewStages")) {
            if (!identifier(stage.path("id").asText()) || !stages.add(stage.path("id").asText())
                    || !stage.path("permittedActions").isArray() || !stage.path("nextStages").isArray()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid review stage");
            }
        }
        for (JsonNode stage : policy.path("reviewStages")) {
            for (JsonNode target : stage.path("nextStages")) {
                if (!stages.contains(target.asText())) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown review target");
                }
            }
            JsonNode targets = stage.path("actionTargets");
            if (!targets.isMissingNode()) {
                if (!targets.isObject()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Action targets must be an object");
                for (String action : List.of("ESCALATE", "RETURN_FOR_REWORK")) {
                    JsonNode route = targets.path(action);
                    if (!route.isMissingNode()) {
                        if (!route.isArray() || !contains(stage.path("permittedActions"), action)) {
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid action targets");
                        }
                        for (JsonNode target : route) if (!stages.contains(target.asText())) {
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown action target");
                        }
                    }
                }
            }
        }
        if (!stages.contains(policy.path("closureRules").path("humanApprovalStage").asText())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown closure approval stage");
        }
        Set<String> destinationIds = new HashSet<>();
        if (!policy.path("destinations").isMissingNode() && !policy.path("destinations").isArray()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Destinations must be an array");
        }
        for (JsonNode destination : policy.path("destinations")) {
            String kind = destination.path("kind").asText();
            if (!identifier(destination.path("destinationId").asText())
                    || !destinationIds.add(destination.path("destinationId").asText())
                    || !identifier(destination.path("sectionId").asText())
                    || !List.of("BUILT_IN", "EXTERNAL_SECTION").contains(kind)
                    || !"CASE_INVESTIGATION".equals(destination.path("workspace").asText())
                    || !destination.path("contextMapping").isObject()
                    || !"case.id".equals(destination.path("contextMapping").path("caseId").asText())
                    || (destination.has("focusField") && !identifier(destination.path("focusField").asText()))
                    || ("EXTERNAL_SECTION".equals(kind)
                        && !identifier(destination.path("externalSectionRef").asText()))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid navigation destination");
            }
            for (var entry : destination.path("contextMapping").properties()) {
                if (!identifier(entry.getKey()) || !List.of("case.id", "case.primarySubjectId", "case.tenantId", "step.id")
                        .contains(entry.getValue().asText())) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported navigation context mapping");
                }
            }
        }
        Set<String> stepIds = new HashSet<>();
        for (JsonNode step : policy.path("steps")) {
            String id = step.path("id").asText();
            if (step.has("agent") && (!"AGENT_THEN_HUMAN".equals(step.path("owner").asText())
                    || !"BANK_DOCUMENT_REVIEW".equals(step.path("agent").path("type").asText())
                    || !step.path("agent").path("requireHumanReview").asBoolean())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Bank document agent steps require AGENT_THEN_HUMAN ownership and human review");
            }
            if (!id.matches("[a-z][a-z0-9_]*") || !stepIds.add(id)
                    || !identifier(step.path("destinationId").asText())
                    || !step.path("activation").path("allAlertTypes").isArray()
                    || !step.path("required").isBoolean()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Step ID, destination, activation and required boolean are mandatory");
            }
        }
    }

    private static boolean identifier(String value) {
        return value != null && !value.isBlank() && value.matches("[A-Za-z][A-Za-z0-9_-]*");
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
    public record SimulationRequest(JsonNode document, List<String> alertTypes) {}
    public record DraftView(String tenantId, String policyId, int version, String status) {}
    public record Evaluation(String policyId, int policyVersion, String caseType,
            int joinWindowDays, List<String> requiredStepIds, List<String> optionalStepIds,
            String initialReviewStage) {}
    public record Grouping(String policyId, int policyVersion, int joinWindowDays) {}
}
