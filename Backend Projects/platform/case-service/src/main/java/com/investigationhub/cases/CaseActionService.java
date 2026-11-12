package com.investigationhub.cases;

import com.investigationhub.common.OutboxWriter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
public class CaseActionService {
    private final JdbcClient jdbc;
    private final CaseService cases;
    private final OutboxWriter outbox;
    private final CaseJourneyWriter journey;
    private final RestClient policyClient;
    private final RestClient playbookClient;

    public CaseActionService(JdbcClient jdbc, CaseService cases, OutboxWriter outbox,
            CaseJourneyWriter journey,
            RestClient.Builder clients, @Value("${ih.policy.base-url}") String policyUrl,
            @Value("${ih.playbook.base-url}") String playbookUrl) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.outbox = outbox;
        this.journey = journey;
        this.policyClient = clients.clone().baseUrl(policyUrl).build();
        this.playbookClient = clients.clone().baseUrl(playbookUrl).build();
    }

    public List<ActionOption> available(UUID caseId, String actorId) {
        requireActor(actorId);
        CaseService.CaseView current = cases.get(caseId);
        if (!"OPEN".equals(current.status())) return List.of();
        JsonNode policy = policy(current);
        String stage = stage(current, policy);
        JsonNode config = stageConfig(policy, stage);
        List<ActionOption> result = new ArrayList<>();
        for (JsonNode actionNode : config.path("permittedActions")) {
            String action = actionNode.asText();
            if ("ESCALATE".equals(action) || "RETURN_FOR_REWORK".equals(action)) {
                JsonNode targets = config.path("actionTargets");
                JsonNode route = targets.isMissingNode() ? config.path("nextStages") : targets.path(action);
                for (JsonNode next : route) {
                    result.add(new ActionOption(action, next.asText(), true, null));
                }
            } else if ("APPROVE_CLOSE".equals(action)) {
                String blocker = closeBlocker(current, policy, actorId, stage);
                result.add(new ActionOption(action, null, blocker == null, blocker));
            }
        }
        return result;
    }

    @Transactional
    public CaseService.CaseView act(UUID caseId, ActionCommand command, String actorId, String correlationId) {
        requireActor(actorId);
        if (command == null || command.actionId() == null || command.action() == null
                || command.reason() == null || command.reason().isBlank() || command.expectedRevision() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action ID, action, expected revision and reason are required");
        }
        ExistingAction prior = existing(command.actionId());
        if (prior != null) return replay(caseId, command, actorId, prior);
        jdbc.sql("select case_id from investigation_case where case_id = :caseId for update")
                .param("caseId", caseId).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        prior = existing(command.actionId());
        if (prior != null) return replay(caseId, command, actorId, prior);
        CaseService.CaseView current = cases.get(caseId);
        if (!"OPEN".equals(current.status()) || current.revision() != command.expectedRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Case status or revision changed");
        }
        JsonNode policy = policy(current);
        String fromStage = stage(current, policy);
        ActionOption allowed = available(caseId, actorId).stream()
                .filter(option -> option.action().equals(command.action())
                        && java.util.Objects.equals(option.toStage(), command.toStage()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Action or target stage is not permitted by the pinned policy"));
        if (!allowed.enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, allowed.reason());
        long nextRevision = current.revision() + 1;
        boolean close = "APPROVE_CLOSE".equals(command.action());
        String toStage = close ? null : command.toStage();
        if (close && command.assignedTo() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Closure cannot assign a reviewer");
        }
        jdbc.sql("""
                update investigation_case set revision = :revision, status = :status,
                  review_stage = :stage, assigned_to = :assignedTo,
                  last_review_actor_id = :actorId, closed_at = case when :close then now() else closed_at end,
                  updated_at = now() where case_id = :caseId
                """)
                .param("revision", nextRevision).param("status", close ? "CLOSED" : "OPEN")
                .param("stage", close ? fromStage : toStage).param("assignedTo", close ? null : command.assignedTo())
                .param("actorId", actorId).param("close", close).param("caseId", caseId).update();
        jdbc.sql("""
                insert into case_action (action_id, case_id, case_revision, action, from_stage, to_stage,
                  actor_id, assigned_to, reason, policy_id, policy_version)
                values (:actionId, :caseId, :revision, :action, :fromStage, :toStage,
                  :actorId, :assignedTo, :reason, :policyId, :policyVersion)
                """)
                .param("actionId", command.actionId()).param("caseId", caseId).param("revision", nextRevision)
                .param("action", command.action()).param("fromStage", fromStage).param("toStage", toStage)
                .param("actorId", actorId).param("assignedTo", command.assignedTo())
                .param("reason", command.reason()).param("policyId", current.policyId())
                .param("policyVersion", current.policyVersion()).update();
        Map<String, Object> event = new HashMap<>();
        event.put("caseId", caseId.toString());
        event.put("actionId", command.actionId().toString());
        event.put("action", command.action());
        event.put("fromStage", fromStage);
        event.put("toStage", toStage);
        event.put("assignedTo", command.assignedTo());
        event.put("caseRevision", nextRevision);
        String eventType = close ? "CaseClosed" : "CaseRouted";
        UUID eventId = outbox.append(eventType, current.tenantId(), caseId.toString(),
                nextRevision, correlationId, actorId, event);
        journey.append(eventId, caseId, nextRevision, eventType, actorId, event);
        return cases.get(caseId);
    }

    public List<ActionHistory> history(UUID caseId) {
        cases.get(caseId);
        return jdbc.sql("""
                select action_id, case_revision, action, from_stage, to_stage,
                  actor_id, assigned_to, reason, created_at from case_action
                where case_id = :caseId order by case_revision
                """).param("caseId", caseId)
                .query((rs, row) -> new ActionHistory(rs.getObject("action_id", UUID.class),
                        rs.getLong("case_revision"), rs.getString("action"), rs.getString("from_stage"),
                        rs.getString("to_stage"), rs.getString("actor_id"), rs.getString("assigned_to"),
                        rs.getString("reason"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private JsonNode policy(CaseService.CaseView current) {
        JsonNode result = policyClient.get().uri(
                "/v1/policies/{policyId}/versions/{version}?tenantId={tenantId}",
                current.policyId(), current.policyVersion(), current.tenantId()).retrieve().body(JsonNode.class);
        if (result == null) throw new IllegalStateException("Pinned policy returned no body");
        return result;
    }

    private ExistingAction existing(UUID actionId) {
        return jdbc.sql("""
                select case_id, case_revision, action, to_stage, actor_id, assigned_to, reason
                from case_action where action_id = :actionId
                """).param("actionId", actionId)
                .query((rs, row) -> new ExistingAction(rs.getObject("case_id", UUID.class),
                        rs.getLong("case_revision"), rs.getString("action"), rs.getString("to_stage"),
                        rs.getString("actor_id"), rs.getString("assigned_to"), rs.getString("reason")))
                .optional().orElse(null);
    }

    private CaseService.CaseView replay(UUID caseId, ActionCommand command, String actorId, ExistingAction prior) {
        if (!prior.caseId().equals(caseId) || prior.caseRevision() != command.expectedRevision() + 1
                || !prior.action().equals(command.action())
                || !java.util.Objects.equals(prior.toStage(), command.toStage())
                || !prior.actorId().equals(actorId)
                || !java.util.Objects.equals(prior.assignedTo(), command.assignedTo())
                || !prior.reason().equals(command.reason())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Action ID reused with different details");
        }
        return cases.get(caseId);
    }

    private String stage(CaseService.CaseView current, JsonNode policy) {
        return current.reviewStage() == null ? policy.path("reviewStages").get(0).path("id").asText()
                : current.reviewStage();
    }

    private JsonNode stageConfig(JsonNode policy, String stage) {
        for (JsonNode candidate : policy.path("reviewStages")) {
            if (stage.equals(candidate.path("id").asText())) return candidate;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Current review stage is absent from pinned policy");
    }

    private String closeBlocker(CaseService.CaseView current, JsonNode policy, String actorId, String stage) {
        if (!stage.equals(policy.path("closureRules").path("humanApprovalStage").asText())) {
            return "Policy requires approval at another stage";
        }
        if (policy.path("closureRules").path("requireIndependentCheck").asBoolean()) {
            String previousActor = jdbc.sql("select last_review_actor_id from investigation_case where case_id = :caseId")
                    .param("caseId", current.caseId()).query(String.class).optional().orElse(null);
            if (previousActor == null || previousActor.equals(actorId)) {
                return "Independent reviewer required";
            }
        }
        try {
            PlaybookReadiness readiness = playbookClient.get()
                    .uri("/v1/playbooks/cases/{caseId}/readiness", current.caseId())
                    .retrieve().body(PlaybookReadiness.class);
            if (readiness == null || !new HashSet<>(readiness.requiredStepIds())
                    .equals(new HashSet<>(current.requiredStepIds()))) return "Playbook is behind the case revision";
            if (!readiness.ready()) return "Required playbook steps remain: " + readiness.pendingStepIds();
            return null;
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) return "Playbook is not ready";
            throw error;
        }
    }

    private void requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Actor ID is required");
        }
    }

    public record ActionOption(String action, String toStage, boolean enabled, String reason) {}
    public record ActionCommand(UUID actionId, long expectedRevision, String action, String toStage,
            String assignedTo, String reason) {}
    public record ActionHistory(UUID actionId, long caseRevision, String action, String fromStage,
            String toStage, String actorId, String assignedTo, String reason, OffsetDateTime createdAt) {}
    private record PlaybookReadiness(UUID caseId, long caseRevision, long playbookRevision,
            List<String> requiredStepIds, List<String> pendingStepIds, boolean ready) {}
    private record ExistingAction(UUID caseId, long caseRevision, String action, String toStage,
            String actorId, String assignedTo, String reason) {}
}
