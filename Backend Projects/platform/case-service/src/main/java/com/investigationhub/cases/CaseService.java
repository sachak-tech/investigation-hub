package com.investigationhub.cases;

import com.investigationhub.common.OutboxWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CaseService {
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final CaseJourneyWriter journey;
    private final RestClient policyClient;
    private final JsonMapper json;

    public CaseService(JdbcClient jdbc, OutboxWriter outbox, CaseJourneyWriter journey,
            RestClient.Builder clients, JsonMapper json,
            @Value("${ih.policy.base-url}") String policyUrl) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.journey = journey;
        this.json = json;
        this.policyClient = clients.baseUrl(policyUrl).build();
    }

    @Transactional
    public CaseView ingest(IngestAlert command) {
        if (command.alertId() == null || command.tenantId() == null || command.subjectId() == null
                || command.alertType() == null || command.detectedAt() == null
                || command.correlationId() == null || command.sourceEventId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alert identity and context are required");
        }
        if (!command.subjectVerified()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Subject must be verified before automatic case joining");
        }
        UUID linkedCase = jdbc.sql("select case_id from case_alert where alert_id = :alertId")
                .param("alertId", command.alertId()).query(UUID.class).optional().orElse(null);
        if (linkedCase != null) return get(linkedCase);

        jdbc.sql("insert into subject_lock (tenant_id, subject_id) values (:tenantId, :subjectId) on conflict do nothing")
                .param("tenantId", command.tenantId()).param("subjectId", command.subjectId()).update();
        jdbc.sql("select subject_id from subject_lock where tenant_id = :tenantId and subject_id = :subjectId for update")
                .param("tenantId", command.tenantId()).param("subjectId", command.subjectId())
                .query(String.class).single();

        // Recheck after the subject lock: another delivery may have linked this alert first.
        linkedCase = jdbc.sql("select case_id from case_alert where alert_id = :alertId")
                .param("alertId", command.alertId()).query(UUID.class).optional().orElse(null);
        if (linkedCase != null) return get(linkedCase);

        PolicyGrouping grouping = grouping(command.tenantId());
        CaseRow existing = jdbc.sql("""
                select case_id, case_type, revision from investigation_case
                where tenant_id = :tenantId and subject_id = :subjectId and status = 'OPEN'
                  and policy_id = :policyId and policy_version = :policyVersion
                  and created_at >= now() - (:joinDays * interval '1 day')
                order by created_at desc limit 1 for update
                """)
                .param("tenantId", command.tenantId()).param("subjectId", command.subjectId())
                .param("policyId", grouping.policyId()).param("policyVersion", grouping.policyVersion())
                .param("joinDays", grouping.joinWindowDays())
                .query((rs, row) -> new CaseRow(rs.getObject("case_id", UUID.class),
                        rs.getString("case_type"), rs.getLong("revision")))
                .optional().orElse(null);

        UUID caseId;
        long revision;
        String fromType = null;
        PolicyEvaluation finalEvaluation;
        if (existing == null) {
            PolicyEvaluation initial = evaluate(command.tenantId(), List.of(command.alertType()));
            caseId = UUID.randomUUID();
            revision = 1;
            finalEvaluation = initial;
            jdbc.sql("""
                    insert into investigation_case
                      (case_id, tenant_id, subject_id, case_type, status, revision, policy_id, policy_version, required_steps, optional_steps, review_stage)
                    values (:caseId, :tenantId, :subjectId, :caseType, 'OPEN', 1, :policyId, :policyVersion,
                            cast(:requiredSteps as jsonb), cast(:optionalSteps as jsonb), :reviewStage)
                    """)
                    .param("caseId", caseId).param("tenantId", command.tenantId())
                    .param("subjectId", command.subjectId()).param("caseType", initial.caseType())
                    .param("policyId", initial.policyId()).param("policyVersion", initial.policyVersion())
                    .param("requiredSteps", json.writeValueAsString(initial.requiredStepIds()))
                    .param("optionalSteps", json.writeValueAsString(initial.optionalStepIds()))
                    .param("reviewStage", initial.initialReviewStage())
                    .update();
        } else {
            caseId = existing.caseId();
            revision = existing.revision() + 1;
            fromType = existing.caseType();
            List<String> alertTypes = new ArrayList<>(jdbc.sql("select alert_type from case_alert where case_id = :caseId")
                    .param("caseId", caseId).query(String.class).list());
            alertTypes.add(command.alertType());
            finalEvaluation = evaluate(command.tenantId(), alertTypes);
            jdbc.sql("""
                    update investigation_case set case_type = :caseType, revision = :revision,
                      required_steps = cast(:requiredSteps as jsonb),
                      optional_steps = cast(:optionalSteps as jsonb), updated_at = now()
                    where case_id = :caseId
                    """)
                    .param("caseType", finalEvaluation.caseType()).param("revision", revision)
                    .param("requiredSteps", json.writeValueAsString(finalEvaluation.requiredStepIds()))
                    .param("optionalSteps", json.writeValueAsString(finalEvaluation.optionalStepIds()))
                    .param("caseId", caseId).update();
        }

        jdbc.sql("""
                insert into case_alert (alert_id, case_id, tenant_id, alert_type, detected_at)
                values (:alertId, :caseId, :tenantId, :alertType, :detectedAt)
                """)
                .param("alertId", command.alertId()).param("caseId", caseId)
                .param("tenantId", command.tenantId()).param("alertType", command.alertType())
                .param("detectedAt", OffsetDateTime.ofInstant(command.detectedAt(), ZoneOffset.UTC)).update();
        Map<String, Object> linkPayload = Map.of("caseId", caseId.toString(),
                "alertId", command.alertId().toString(),
                "linkReason", existing == null ? "New case" : "Verified subject and join window",
                "sourceEventId", command.sourceEventId().toString());
        UUID linkEventId = outbox.append("CaseAlertLinked", command.tenantId(), caseId.toString(), revision,
                command.correlationId(), "case-service", linkPayload);
        journey.append(linkEventId, caseId, revision, "CaseAlertLinked", "case-service", linkPayload);
        if (fromType != null && !fromType.equals(finalEvaluation.caseType())) {
            Map<String, Object> classificationPayload = Map.of("caseId", caseId.toString(),
                    "fromType", fromType, "toType", finalEvaluation.caseType(),
                    "triggerAlertId", command.alertId().toString());
            UUID classificationEventId = outbox.append("CaseClassificationChanged", command.tenantId(),
                    caseId.toString(), revision, command.correlationId(), "case-service", classificationPayload);
            journey.append(classificationEventId, caseId, revision,
                    "CaseClassificationChanged", "case-service", classificationPayload);
        }
        return get(caseId);
    }

    public CaseView get(UUID caseId) {
        CaseRowFull row = jdbc.sql("""
                select case_id, tenant_id, subject_id, case_type, status, revision, policy_id, policy_version,
                  required_steps::text as required_steps, optional_steps::text as optional_steps,
                  review_stage, assigned_to, closed_at
                from investigation_case where case_id = :caseId
                """)
                .param("caseId", caseId)
                .query((rs, index) -> new CaseRowFull(rs.getObject("case_id", UUID.class),
                        rs.getString("tenant_id"), rs.getString("subject_id"), rs.getString("case_type"),
                        rs.getString("status"), rs.getLong("revision"), rs.getString("policy_id"),
                        rs.getInt("policy_version"), rs.getString("required_steps"),
                        rs.getString("optional_steps"),
                        rs.getString("review_stage"), rs.getString("assigned_to"),
                        rs.getObject("closed_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        List<String> alertTypes = jdbc.sql("select alert_type from case_alert where case_id = :caseId order by linked_at")
                .param("caseId", caseId).query(String.class).list();
        List<String> requiredSteps = new ArrayList<>();
        for (JsonNode step : json.readTree(row.requiredSteps())) requiredSteps.add(step.asText());
        List<String> optionalSteps = new ArrayList<>();
        for (JsonNode step : json.readTree(row.optionalSteps())) optionalSteps.add(step.asText());
        return new CaseView(row.caseId(), row.tenantId(), row.subjectId(), row.caseType(), row.status(),
                row.revision(), row.policyId(), row.policyVersion(), alertTypes, requiredSteps, optionalSteps,
                row.reviewStage(), row.assignedTo(), row.closedAt());
    }

    public CaseView byAlert(UUID alertId) {
        UUID caseId = jdbc.sql("select case_id from case_alert where alert_id = :alertId")
                .param("alertId", alertId).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not linked yet"));
        return get(caseId);
    }

    private PolicyEvaluation evaluate(String tenantId, List<String> alertTypes) {
        PolicyEvaluation result = policyClient.post().uri("/v1/policies/evaluate-case")
                .body(new EvaluationRequest(tenantId, alertTypes)).retrieve().body(PolicyEvaluation.class);
        if (result == null) throw new IllegalStateException("Policy evaluation returned no body");
        return result;
    }

    private PolicyGrouping grouping(String tenantId) {
        PolicyGrouping result = policyClient.get()
                .uri(uri -> uri.path("/v1/policies/grouping").queryParam("tenantId", tenantId).build())
                .retrieve().body(PolicyGrouping.class);
        if (result == null) throw new IllegalStateException("Policy grouping returned no body");
        return result;
    }

    private record CaseRow(UUID caseId, String caseType, long revision) {}
    private record CaseRowFull(UUID caseId, String tenantId, String subjectId, String caseType,
            String status, long revision, String policyId, int policyVersion, String requiredSteps,
            String optionalSteps, String reviewStage, String assignedTo, OffsetDateTime closedAt) {}
    private record EvaluationRequest(String tenantId, List<String> alertTypes) {}
    private record PolicyEvaluation(String policyId, int policyVersion, String caseType,
            int joinWindowDays, List<String> requiredStepIds, List<String> optionalStepIds,
            String initialReviewStage) {}
    private record PolicyGrouping(String policyId, int policyVersion, int joinWindowDays) {}
    public record IngestAlert(UUID alertId, String tenantId, String subjectId, boolean subjectVerified,
            String alertType, Instant detectedAt, String correlationId, UUID sourceEventId) {}
    public record CaseView(UUID caseId, String tenantId, String subjectId, String caseType,
            String status, long revision, String policyId, int policyVersion, List<String> alertTypes,
            List<String> requiredStepIds, List<String> optionalStepIds,
            String reviewStage, String assignedTo, OffsetDateTime closedAt) {}
}
