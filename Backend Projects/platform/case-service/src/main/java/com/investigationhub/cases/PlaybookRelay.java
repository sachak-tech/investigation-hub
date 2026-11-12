package com.investigationhub.cases;

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
public class PlaybookRelay {
    private static final Logger log = LoggerFactory.getLogger(PlaybookRelay.class);
    private final JdbcClient jdbc;
    private final CaseService cases;
    private final RestClient playbookClient;

    public PlaybookRelay(JdbcClient jdbc, CaseService cases, RestClient.Builder clients,
            @Value("${ih.playbook.base-url}") String playbookUrl) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.playbookClient = clients.baseUrl(playbookUrl).build();
    }

    @Scheduled(fixedDelayString = "${ih.relay.delay-ms:1000}")
    public void relay() {
        List<Pending> pending = jdbc.sql("""
                select event_id, event_type, tenant_id, aggregate_id, aggregate_revision, correlation_id from outbox_event
                where event_type in ('CaseAlertLinked', 'CaseClosed') and delivered_at is null
                order by created_at limit 20
                """).query((rs, row) -> new Pending(rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), rs.getString("tenant_id"),
                UUID.fromString(rs.getString("aggregate_id")), rs.getLong("aggregate_revision"),
                rs.getString("correlation_id"))).list();
        for (Pending event : pending) {
            try {
                if ("CaseClosed".equals(event.type())) {
                    playbookClient.post().uri("/v1/playbooks/cases/{caseId}/close", event.caseId())
                            .header("X-Correlation-Id", event.correlationId())
                            .header("X-Event-Id", event.eventId().toString())
                            .body(new CloseCommand(event.tenantId(), event.revision()))
                            .retrieve().toBodilessEntity();
                } else {
                    CaseService.CaseView current = cases.get(event.caseId());
                    if ("CLOSED".equals(current.status())) {
                        // A later close event supersedes a delayed alert snapshot.
                    } else {
                        playbookClient.post().uri("/v1/playbooks/cases/{caseId}/reconcile", event.caseId())
                                .header("X-Correlation-Id", event.correlationId())
                                .header("X-Event-Id", event.eventId().toString())
                                .body(current).retrieve().toBodilessEntity();
                    }
                }
                jdbc.sql("update outbox_event set delivered_at = now(), attempts = attempts + 1, last_error = null where event_id = :id")
                        .param("id", event.eventId()).update();
            } catch (Exception error) {
                jdbc.sql("update outbox_event set attempts = attempts + 1, last_error = :error where event_id = :id")
                        .param("id", event.eventId())
                        .param("error", error.getClass().getSimpleName() + ": " + error.getMessage())
                        .update();
                log.debug("Playbook delivery pending for event {}: {}", event.eventId(), error.toString());
            }
        }
    }

    private record Pending(UUID eventId, String type, String tenantId, UUID caseId,
            long revision, String correlationId) {}
    private record CloseCommand(String tenantId, long caseRevision) {}
}
