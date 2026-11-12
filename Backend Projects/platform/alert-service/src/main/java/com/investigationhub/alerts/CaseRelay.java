package com.investigationhub.alerts;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CaseRelay {
    private static final Logger log = LoggerFactory.getLogger(CaseRelay.class);
    private final JdbcClient jdbc;
    private final RestClient caseClient;

    public CaseRelay(JdbcClient jdbc, RestClient.Builder clients,
            @Value("${ih.case.base-url}") String caseUrl) {
        this.jdbc = jdbc;
        this.caseClient = clients.baseUrl(caseUrl).build();
    }

    @Scheduled(fixedDelayString = "${ih.relay.delay-ms:1000}")
    public void relay() {
        List<Pending> pending = jdbc.sql("""
                select e.event_id, e.correlation_id, a.alert_id, a.tenant_id, a.subject_id,
                       a.subject_verified, a.alert_type, a.detected_at
                from outbox_event e join alert a on a.accepted_event_id = e.event_id
                where e.event_type = 'AlertAccepted' and e.delivered_at is null and a.subject_verified
                order by e.created_at limit 20
                """).query((rs, row) -> new Pending(rs.getObject("event_id", UUID.class),
                rs.getString("correlation_id"), rs.getObject("alert_id", UUID.class),
                rs.getString("tenant_id"), rs.getString("subject_id"),
                rs.getBoolean("subject_verified"), rs.getString("alert_type"),
                rs.getObject("detected_at", OffsetDateTime.class).toInstant())).list();
        for (Pending event : pending) {
            try {
                caseClient.post().uri("/v1/cases/ingest-alert")
                        .header("X-Correlation-Id", event.correlationId())
                        .body(new IngestAlert(event.alertId(), event.tenantId(), event.subjectId(),
                                event.subjectVerified(), event.alertType(), event.detectedAt(),
                                event.correlationId(), event.eventId()))
                        .retrieve().toBodilessEntity();
                jdbc.sql("update outbox_event set delivered_at = now(), attempts = attempts + 1, last_error = null where event_id = :id")
                        .param("id", event.eventId()).update();
            } catch (Exception error) {
                jdbc.sql("update outbox_event set attempts = attempts + 1, last_error = :error where event_id = :id")
                        .param("id", event.eventId())
                        .param("error", error.getClass().getSimpleName() + ": " + error.getMessage())
                        .update();
                log.debug("Case delivery pending for event {}: {}", event.eventId(), error.toString());
            }
        }
    }

    private record Pending(UUID eventId, String correlationId, UUID alertId, String tenantId,
            String subjectId, boolean subjectVerified, String alertType, Instant detectedAt) {}
    private record IngestAlert(UUID alertId, String tenantId, String subjectId, boolean subjectVerified,
            String alertType, Instant detectedAt, String correlationId, UUID sourceEventId) {}
}
