package com.investigationhub.alerts;

import com.investigationhub.common.OutboxWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public AlertService(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional
    public AlertView accept(AcceptAlert request, String correlationId, String actorId) {
        if (request.tenantId() == null || request.sourceSystem() == null || request.sourceAlertId() == null
                || request.alertType() == null || request.subjectId() == null || request.detectedAt() == null
                || request.tenantId().isBlank() || request.sourceSystem().isBlank()
                || request.sourceAlertId().isBlank() || request.subjectId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alert identity and context are required");
        }
        UUID alertId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                insert into alert (alert_id, accepted_event_id, tenant_id, source_system, source_alert_id,
                                   alert_type, subject_id, subject_verified, detected_at)
                values (:alertId, :eventId, :tenantId, :sourceSystem, :sourceAlertId,
                        :alertType, :subjectId, :subjectVerified, :detectedAt)
                on conflict (tenant_id, source_system, source_alert_id) do nothing
                """)
                .param("alertId", alertId).param("eventId", eventId)
                .param("tenantId", request.tenantId()).param("sourceSystem", request.sourceSystem())
                .param("sourceAlertId", request.sourceAlertId()).param("alertType", request.alertType())
                .param("subjectId", request.subjectId()).param("subjectVerified", request.subjectVerified())
                .param("detectedAt", OffsetDateTime.ofInstant(request.detectedAt(), ZoneOffset.UTC)).update();
        if (inserted == 0) {
            AlertView existing = jdbc.sql("""
                    select alert_id, accepted_event_id, tenant_id, source_system, source_alert_id,
                           alert_type, subject_id, subject_verified, detected_at
                    from alert where tenant_id = :tenantId and source_system = :sourceSystem
                      and source_alert_id = :sourceAlertId
                    """)
                    .param("tenantId", request.tenantId()).param("sourceSystem", request.sourceSystem())
                    .param("sourceAlertId", request.sourceAlertId()).query(this::map).single();
            if (!existing.alertType().equals(request.alertType())
                    || !existing.subjectId().equals(request.subjectId())
                    || existing.subjectVerified() != request.subjectVerified()
                    || !existing.detectedAt().equals(request.detectedAt())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Source alert ID reused with different facts");
            }
            return existing;
        }
        outbox.append(eventId, "AlertAccepted", request.tenantId(), alertId.toString(), 1,
                correlationId, actorId,
                Map.of("alertId", alertId.toString(), "alertType", request.alertType(),
                        "subjectId", request.subjectId(), "detectedAt", request.detectedAt().toString(),
                        "subjectVerified", request.subjectVerified()));
        return new AlertView(alertId, eventId, request.tenantId(), request.sourceSystem(),
                request.sourceAlertId(), request.alertType(), request.subjectId(),
                request.subjectVerified(), request.detectedAt());
    }

    public AlertView get(UUID alertId) {
        return jdbc.sql("""
                select alert_id, accepted_event_id, tenant_id, source_system, source_alert_id,
                       alert_type, subject_id, subject_verified, detected_at
                from alert where alert_id = :alertId
                """).param("alertId", alertId).query(this::map).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
    }

    private AlertView map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AlertView(rs.getObject("alert_id", UUID.class), rs.getObject("accepted_event_id", UUID.class),
                rs.getString("tenant_id"), rs.getString("source_system"), rs.getString("source_alert_id"),
                rs.getString("alert_type"), rs.getString("subject_id"), rs.getBoolean("subject_verified"),
                rs.getObject("detected_at", OffsetDateTime.class).toInstant());
    }

    public record AcceptAlert(String tenantId, String sourceSystem, String sourceAlertId,
            String alertType, String subjectId, boolean subjectVerified, Instant detectedAt) {}
    public record AlertView(UUID alertId, UUID acceptedEventId, String tenantId, String sourceSystem,
            String sourceAlertId, String alertType, String subjectId, boolean subjectVerified, Instant detectedAt) {}
}
