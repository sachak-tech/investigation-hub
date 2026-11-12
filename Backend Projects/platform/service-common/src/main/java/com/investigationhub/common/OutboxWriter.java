package com.investigationhub.common;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

public class OutboxWriter {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String table;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json, String schema) {
        this.jdbc = jdbc;
        this.json = json;
        if (!schema.isEmpty() && !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid outbox schema name");
        }
        this.table = schema.isEmpty() ? "outbox_event" : schema + ".outbox_event";
    }

    public UUID append(String eventType, String tenantId, String aggregateId, long aggregateRevision,
            String correlationId, String actorId, Object payload) {
        UUID eventId = UUID.randomUUID();
        append(eventId, eventType, tenantId, aggregateId, aggregateRevision, correlationId, actorId, payload);
        return eventId;
    }

    public void append(UUID eventId, String eventType, String tenantId, String aggregateId, long aggregateRevision,
            String correlationId, String actorId, Object payload) {
        String body = json.writeValueAsString(payload);
        jdbc.sql("""
                insert into %s
                  (event_id, event_type, tenant_id, aggregate_id, aggregate_revision,
                   correlation_id, actor_id, payload)
                values (:eventId, :eventType, :tenantId, :aggregateId, :aggregateRevision,
                        :correlationId, :actorId, cast(:payload as jsonb))
                """.formatted(table))
                .param("eventId", eventId)
                .param("eventType", eventType)
                .param("tenantId", tenantId)
                .param("aggregateId", aggregateId)
                .param("aggregateRevision", aggregateRevision)
                .param("correlationId", correlationId)
                .param("actorId", actorId)
                .param("payload", body)
                .update();
    }
}
