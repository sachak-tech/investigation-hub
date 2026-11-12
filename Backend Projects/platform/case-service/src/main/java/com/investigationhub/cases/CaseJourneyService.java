package com.investigationhub.cases;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CaseJourneyService {
    private final JdbcClient jdbc;
    private final CaseService cases;
    private final CaseActionService actions;
    private final JsonMapper json;
    private final RestClient policyClient;

    public CaseJourneyService(JdbcClient jdbc, CaseService cases, CaseActionService actions,
            JsonMapper json, RestClient.Builder clients,
            @Value("${ih.policy.base-url}") String policyUrl) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.actions = actions;
        this.json = json;
        this.policyClient = clients.clone().baseUrl(policyUrl).build();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Journey get(UUID caseId) {
        CaseService.CaseView current = cases.get(caseId);
        JsonNode policy = policyClient.get().uri(
                "/v1/policies/{policyId}/versions/{version}?tenantId={tenantId}",
                current.policyId(), current.policyVersion(), current.tenantId())
                .retrieve().body(JsonNode.class);
        if (policy == null || !policy.path("reviewStages").isArray()
                || policy.path("reviewStages").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned policy has no review stages");
        }
        List<Stage> stages = new ArrayList<>();
        for (JsonNode stage : policy.path("reviewStages")) {
            stages.add(new Stage(stage.path("id").asText(), strings(stage.path("permittedActions")),
                    strings(stage.path("nextStages"))));
        }
        String initialStage = stages.get(0).id();
        String currentStage = current.reviewStage() == null ? initialStage : current.reviewStage();

        Map<UUID, AlertInfo> alerts = new HashMap<>();
        jdbc.sql("select alert_id, alert_type, detected_at from case_alert where case_id = :caseId")
                .param("caseId", caseId)
                .query((rs, row) -> new AlertInfo(rs.getObject("alert_id", UUID.class),
                        rs.getString("alert_type"), rs.getObject("detected_at", OffsetDateTime.class)))
                .list().forEach(alert -> alerts.put(alert.id(), alert));
        Map<UUID, CaseActionService.ActionHistory> actionById = new HashMap<>();
        actions.history(caseId).forEach(action -> actionById.put(action.actionId(), action));

        List<StoredEvent> stored = jdbc.sql("""
                select event_id, event_type, case_revision, actor_id,
                  payload::text as payload, occurred_at
                from case_journey_event where case_id = :caseId
                  and event_type in ('CaseAlertLinked', 'CaseClassificationChanged', 'CaseRouted', 'CaseClosed')
                  and case_revision <= :revision
                order by case_revision,
                  case when event_type = 'CaseAlertLinked' then 0 else 1 end, occurred_at, event_id
                """).param("caseId", caseId).param("revision", current.revision())
                .query((rs, row) -> new StoredEvent(rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"), rs.getLong("case_revision"),
                        rs.getString("actor_id"), json.readTree(rs.getString("payload")),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();

        String initialCaseType = stored.stream()
                .filter(event -> "CaseClassificationChanged".equals(event.type()))
                .map(event -> value(event.payload(), "fromType"))
                .findFirst().orElse(current.caseType());
        List<JourneyEvent> events = new ArrayList<>();
        for (StoredEvent event : stored) {
            JsonNode payload = event.payload();
            if ("CaseAlertLinked".equals(event.type())) {
                UUID alertId = UUID.fromString(payload.path("alertId").asText());
                AlertInfo alert = alerts.get(alertId);
                boolean created = "New case".equals(value(payload, "linkReason"));
                events.add(new JourneyEvent(event.id(), created ? "CASE_CREATED" : "ALERT_LINKED",
                        event.revision(), event.at(), event.actorId(), null, created ? initialStage : null,
                        null, created ? initialCaseType : null, alertId,
                        alert == null ? null : alert.type(), alert == null ? null : alert.detectedAt(),
                        null, null));
            } else if ("CaseClassificationChanged".equals(event.type())) {
                events.add(new JourneyEvent(event.id(), "CASE_CLASSIFICATION_CHANGED", event.revision(),
                        event.at(), event.actorId(), null, null, value(payload, "fromType"),
                        value(payload, "toType"), null, null, null, null, null));
            } else {
                UUID actionId = UUID.fromString(payload.path("actionId").asText());
                CaseActionService.ActionHistory action = actionById.get(actionId);
                if (action == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Case action audit is missing for journey event " + event.id());
                }
                events.add(new JourneyEvent(event.id(), action.action(), event.revision(), event.at(),
                        action.actorId(), action.fromStage(), action.toStage(), null, null,
                        null, null, null, action.assignedTo(), action.reason()));
            }
        }
        return new Journey(caseId, current.revision(), current.tenantId(), current.caseType(),
                current.status(), currentStage, current.assignedTo(), current.policyId(),
                current.policyVersion(), initialStage, stages, events);
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) result.add(item.asText());
        return result;
    }

    private static String value(JsonNode object, String field) {
        JsonNode item = object.path(field);
        return item.isMissingNode() || item.isNull() ? null : item.asText();
    }

    public record Journey(UUID caseId, long caseRevision, String tenantId, String caseType,
            String status, String currentStage, String assignedTo, String policyId,
            int policyVersion, String initialStage, List<Stage> stages, List<JourneyEvent> events) {}
    public record Stage(String id, List<String> permittedActions, List<String> nextStages) {}
    public record JourneyEvent(UUID eventId, String type, long caseRevision, OffsetDateTime occurredAt,
            String actorId, String fromStage, String toStage, String fromCaseType, String toCaseType,
            UUID alertId, String alertType, OffsetDateTime alertDetectedAt, String assignedTo,
            String reason) {}
    private record StoredEvent(UUID id, String type, long revision, String actorId,
            JsonNode payload, OffsetDateTime at) {}
    private record AlertInfo(UUID id, String type, OffsetDateTime detectedAt) {}
}
