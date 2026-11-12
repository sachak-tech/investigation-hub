package com.investigationhub.cases;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CaseJourneyWriter {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public CaseJourneyWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(UUID eventId, UUID caseId, long revision, String type,
            String actorId, Object payload) {
        jdbc.sql("""
                insert into case_journey_event
                  (event_id, case_id, case_revision, event_type, actor_id, payload)
                values (:eventId, :caseId, :revision, :type, :actorId, cast(:payload as jsonb))
                """).param("eventId", eventId).param("caseId", caseId).param("revision", revision)
                .param("type", type).param("actorId", actorId)
                .param("payload", json.writeValueAsString(payload)).update();
    }
}
