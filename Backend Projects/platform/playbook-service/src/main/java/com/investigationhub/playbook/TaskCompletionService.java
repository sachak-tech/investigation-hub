package com.investigationhub.playbook;

import com.investigationhub.common.OutboxWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.flowable.cmmn.api.CmmnTaskService;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TaskCompletionService {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final PlaybookService playbooks;
    private final CmmnTaskService tasks;
    private final OutboxWriter outbox;
    private final RestClient cases;
    private final RestClient policies;

    public TaskCompletionService(JdbcClient jdbc, JsonMapper json, PlaybookService playbooks,
            CmmnTaskService tasks, OutboxWriter outbox, RestClient.Builder clients,
            @Value("${ih.case.base-url}") String caseUrl, @Value("${ih.policy.base-url}") String policyUrl) {
        this.jdbc = jdbc; this.json = json; this.playbooks = playbooks; this.tasks = tasks; this.outbox = outbox;
        cases = clients.clone().baseUrl(caseUrl).build(); policies = clients.clone().baseUrl(policyUrl).build();
    }

    @Transactional
    public DocumentView register(UUID caseId, DocumentCommand command, String tenant, String actor) {
        identity(tenant, actor);
        JsonNode current = caseContext(caseId, tenant);
        playbooks.get(caseId);
        if (command == null || command.documentId() == null || blank(command.title())
                || blank(command.sourceReference()) || blank(command.content())
                || command.content().length() > 1_000_000) fail(400, "Document ID, title, source reference and text are required (maximum 1M characters)");
        String content = command.content().replace("\r\n", "\n").replace('\r', '\n');
        String hash = sha256(content);
        Document existing = findDocument(command.documentId());
        if (existing != null) return documentReplay(existing, caseId, tenant, actor, command, content);
        if (!"OPEN".equals(current.path("status").asText())) fail(409, "Case is closed");
        jdbc.sql("""
                insert into investigation_playbook.investigation_document
                  (document_id, case_id, tenant_id, title, source_reference, content, sha256, registered_by)
                values (:id, :caseId, :tenant, :title, :source, :content, :hash, :actor)
                on conflict (document_id) do nothing
                """).param("id", command.documentId()).param("caseId", caseId).param("tenant", tenant)
                .param("title", command.title()).param("source", command.sourceReference())
                .param("content", content).param("hash", hash).param("actor", actor).update();
        return documentReplay(findDocument(command.documentId()), caseId, tenant, actor, command, content);
    }

    public List<DocumentView> documents(UUID caseId, String tenant) {
        caseContext(caseId, tenant);
        return jdbc.sql("select document_id from investigation_playbook.investigation_document where case_id = :id order by registered_at")
                .param("id", caseId).query(UUID.class).list().stream().map(this::findDocument).map(this::view).toList();
    }

    public List<Receipt> history(UUID caseId, String tenant) {
        caseContext(caseId, tenant);
        return jdbc.sql("select receipt::text from investigation_playbook.step_completion where case_id = :id order by completed_at, command_id")
                .param("id", caseId).query(String.class).list().stream()
                .map(raw -> json.readValue(raw, Receipt.class)).toList();
    }

    @Transactional
    public Receipt complete(UUID caseId, String stepId, Envelope envelope, String tenant,
            String actor, String correlation) {
        return completeInternal(caseId, stepId, envelope, tenant, actor, correlation, null);
    }

    @Transactional
    public Receipt completeReviewed(UUID caseId, String stepId, Envelope envelope, String tenant,
            String actor, String correlation, UUID agentRunId) {
        return completeInternal(caseId, stepId, envelope, tenant, actor, correlation, agentRunId);
    }

    private Receipt completeInternal(UUID caseId, String stepId, Envelope envelope, String tenant,
            String actor, String correlation, UUID agentRunId) {
        identity(tenant, actor);
        if (envelope == null || envelope.submission() == null || envelope.currentCase() == null) fail(400, "Completion envelope is required");
        Command command = envelope.submission();
        if (command.commandId() == null || blank(command.taskId()) || blank(command.findings())
                || command.findings().length() > 100_000 || command.citations() == null
                || command.expectedCaseRevision() < 1 || command.expectedPlaybookRevision() < 1) fail(400, "Incomplete completion submission");
        JsonNode current = envelope.currentCase();
        if (!caseId.toString().equals(current.path("caseId").asText())
                || !tenant.equals(current.path("tenantId").asText())) fail(404, "Case not found");
        playbooks.lockCase(caseId);
        JsonNode request = json.readTree(json.writeValueAsString(command));
        Stored prior = jdbc.sql("select case_id, step_id, actor_id, request::text, receipt::text from investigation_playbook.step_completion where command_id = :id")
                .param("id", command.commandId()).query((rs, row) -> new Stored(rs.getObject("case_id", UUID.class),
                        rs.getString("step_id"), rs.getString("actor_id"), rs.getString("request"), rs.getString("receipt")))
                .optional().orElse(null);
        if (prior != null) {
            if (!prior.caseId().equals(caseId) || !prior.stepId().equals(stepId) || !prior.actor().equals(actor)
                    || !request.equals(json.readTree(prior.request()))) fail(409, "Command ID reused with different details");
            Receipt receipt = json.readValue(prior.receipt(), Receipt.class);
            if (!Objects.equals(receipt.agentRunId(), agentRunId)) fail(409, "Command belongs to another agent review");
            return receipt;
        }
        PlaybookService.PlaybookView playbook = playbooks.get(caseId);
        if (!"OPEN".equals(current.path("status").asText()) || !"OPEN".equals(playbook.status())
                || current.path("revision").asLong() != command.expectedCaseRevision()
                || playbook.playbookRevision() != command.expectedPlaybookRevision()) fail(409, "Case or playbook revision/status changed");
        if (!strings(current.path("requiredStepIds")).equals(playbook.requiredStepIds())
                || !strings(current.path("optionalStepIds")).equals(playbook.optionalStepIds())) fail(409, "Playbook reconciliation is pending");
        if (!playbook.requiredStepIds().contains(stepId) && !playbook.optionalStepIds().contains(stepId)) fail(404, "Step is not selected for this case");
        JsonNode policy = policies.get().uri("/v1/policies/{id}/versions/{version}?tenantId={tenant}",
                current.path("policyId").asText(), current.path("policyVersion").asInt(), tenant).retrieve().body(JsonNode.class);
        if (policy == null) fail(409, "Pinned policy unavailable");
        JsonNode step = null;
        for (JsonNode candidate : policy.path("steps")) if (stepId.equals(candidate.path("id").asText())) step = candidate;
        if (step == null) fail(409, "Step absent from pinned policy");
        if (step.has("agent") && agentRunId == null) fail(409, "This step requires acceptance of agent findings through the agent-review endpoint");
        if (!List.of("HUMAN", "AGENT_THEN_HUMAN").contains(step.path("owner").asText())) fail(422, "This endpoint completes human review tasks only");
        String stage = current.path("reviewStage").asText();
        if (stage.isBlank()) stage = policy.path("reviewStages").get(0).path("id").asText();
        boolean permitted = false;
        for (JsonNode candidate : policy.path("reviewStages")) if (stage.equals(candidate.path("id").asText())) {
            permitted = strings(candidate.path("permittedActions")).contains("COMPLETE_STEP");
        }
        if (!permitted) fail(422, "Current review stage does not permit task completion");
        Task task = tasks.createTaskQuery().caseInstanceId(playbook.flowableCaseInstanceId()).taskId(command.taskId()).singleResult();
        if (task == null) fail(409, "Active task not found in this case");
        if (!playbooks.selectedPlanItem(playbook, stepId).equals(task.getSubScopeId())) fail(422, "Task does not belong to the selected step");
        if (task.getAssignee() != null && !task.getAssignee().equals(actor)) fail(403, "Task is assigned to another investigator");
        List<VerifiedCitation> citations = validateCitations(caseId, tenant, command.citations());
        Set<String> suppliedTypes = new HashSet<>();
        citations.forEach(citation -> suppliedTypes.add(citation.evidenceType()));
        if (!suppliedTypes.containsAll(strings(step.path("completionEvidence")))) fail(422, "Policy-required evidence types are missing");
        tasks.complete(task.getId());
        long revision = playbook.playbookRevision() + 1;
        jdbc.sql("update investigation_playbook.playbook_instance set playbook_revision = :revision, updated_at = now() where case_id = :id")
                .param("revision", revision).param("id", caseId).update();
        Receipt receipt = new Receipt(command.commandId(), caseId, stepId, command.taskId(), actor,
                command.expectedCaseRevision(), revision, current.path("policyId").asText(),
                current.path("policyVersion").asInt(), command.findings(), citations, agentRunId, OffsetDateTime.now(ZoneOffset.UTC));
        try {
        jdbc.sql("""
                insert into investigation_playbook.step_completion
                  (command_id, case_id, step_id, task_id, actor_id, request, receipt)
                values (:id, :caseId, :stepId, :taskId, :actor, cast(:request as jsonb), cast(:receipt as jsonb))
                """).param("id", command.commandId()).param("caseId", caseId).param("stepId", stepId)
                .param("taskId", command.taskId()).param("actor", actor).param("request", json.writeValueAsString(command))
                .param("receipt", json.writeValueAsString(receipt)).update();
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Completion command or task already recorded", error);
        }
        outbox.append("StepCompleted", tenant, caseId.toString(), revision, correlation, actor, receipt);
        return receipt;
    }

    JsonNode caseContext(UUID caseId, String tenant) {
        JsonNode result = cases.get().uri("/v1/cases/{id}", caseId).retrieve().body(JsonNode.class);
        if (result == null || !tenant.equals(result.path("tenantId").asText())) fail(404, "Case not found");
        return result;
    }
    List<VerifiedCitation> validateCitations(UUID caseId, String tenant, List<Citation> supplied) {
        if (supplied == null) fail(422, "Citations are required");
        List<VerifiedCitation> result = new ArrayList<>();
        for (Citation citation : supplied) {
            if (citation == null || blank(citation.evidenceType()) || citation.documentId() == null || blank(citation.excerpt())) fail(422, "Incomplete evidence citation");
            Document document = findDocument(citation.documentId());
            if (document == null || !document.caseId().equals(caseId) || !document.tenant().equals(tenant)) fail(422, "Evidence document does not belong to this case");
            String[] lines = document.content().split("\n", -1);
            if (citation.startLine() < 1 || citation.endLine() < citation.startLine() || citation.endLine() > lines.length) fail(422, "Citation line range is invalid");
            String exact = String.join("\n", Arrays.copyOfRange(lines, citation.startLine() - 1, citation.endLine()));
            if (!exact.equals(citation.excerpt())) fail(422, "Citation excerpt does not match the supplied document lines");
            result.add(new VerifiedCitation(citation.evidenceType(), document.id(), document.source(), document.hash(), citation.startLine(), citation.endLine(), citation.excerpt()));
        }
        return result;
    }

    List<AgentDocument> agentDocuments(UUID caseId) {
        return jdbc.sql("select document_id from investigation_playbook.investigation_document where case_id = :id order by registered_at, document_id")
                .param("id", caseId).query(UUID.class).list().stream().map(this::findDocument)
                .map(doc -> new AgentDocument(doc.id(), doc.title(), doc.source(), doc.hash(), doc.content())).toList();
    }
    private Document findDocument(UUID id) {
        return jdbc.sql("select * from investigation_playbook.investigation_document where document_id = :id")
                .param("id", id).query((rs, row) -> new Document(id, rs.getObject("case_id", UUID.class),
                        rs.getString("tenant_id"), rs.getString("title"), rs.getString("source_reference"),
                        rs.getString("content"), rs.getString("sha256"), rs.getString("registered_by"),
                        rs.getObject("registered_at", OffsetDateTime.class))).optional().orElse(null);
    }
    private DocumentView documentReplay(Document doc, UUID caseId, String tenant, String actor, DocumentCommand command, String content) {
        if (!doc.caseId().equals(caseId) || !doc.tenant().equals(tenant) || !doc.actor().equals(actor)
                || !doc.content().equals(content) || !doc.title().equals(command.title())
                || !doc.source().equals(command.sourceReference())) fail(409, "Document ID reused with different details");
        return view(doc);
    }
    private DocumentView view(Document doc) { return new DocumentView(doc.id(), doc.caseId(), doc.title(), doc.source(), doc.hash(), doc.actor(), doc.at()); }
    private static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static List<String> strings(JsonNode array) { List<String> list = new ArrayList<>(); for (JsonNode item : array) list.add(item.asText()); return list; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void identity(String tenant, String actor) { if (blank(tenant) || blank(actor)) fail(400, "Tenant and actor are required"); }
    private static void fail(int status, String message) { throw new ResponseStatusException(HttpStatus.valueOf(status), message); }
    public record DocumentCommand(UUID documentId, String title, String sourceReference, String content) {}
    public record DocumentView(UUID documentId, UUID caseId, String title, String sourceReference, String sha256, String registeredBy, OffsetDateTime registeredAt) {}
    private record Document(UUID id, UUID caseId, String tenant, String title, String source, String content, String hash, String actor, OffsetDateTime at) {}
    public record Citation(String evidenceType, UUID documentId, int startLine, int endLine, String excerpt) {}
    public record VerifiedCitation(String evidenceType, UUID documentId, String sourceReference, String documentSha256, int startLine, int endLine, String excerpt) {}
    public record Command(UUID commandId, long expectedCaseRevision, long expectedPlaybookRevision, String taskId, String findings, List<Citation> citations) {}
    public record Envelope(JsonNode currentCase, Command submission) {}
    public record Receipt(UUID commandId, UUID caseId, String stepId, String taskId, String actorId,
            long caseRevision, long playbookRevision, String policyId, int policyVersion, String findings,
            List<VerifiedCitation> citations, UUID agentRunId, OffsetDateTime completedAt) {}
    public record AgentDocument(UUID documentId, String title, String sourceReference, String sha256, String content) {}
    private record Stored(UUID caseId, String stepId, String actor, String request, String receipt) {}
}
