package com.investigationhub.playbook;

import com.investigationhub.common.OutboxWriter;
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
public class AgentReviewService {
    public static final String AGENT_ACTOR = "agent:bank-document-review";
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final PlaybookService playbooks;
    private final TaskCompletionService completions;
    private final CmmnTaskService tasks;
    private final RestClient policies;
    private final OutboxWriter outbox;

    public AgentReviewService(JdbcClient jdbc, JsonMapper json, PlaybookService playbooks,
            TaskCompletionService completions, CmmnTaskService tasks, OutboxWriter outbox,
            RestClient.Builder clients, @Value("${ih.policy.base-url}") String policyUrl) {
        this.jdbc = jdbc; this.json = json; this.playbooks = playbooks; this.completions = completions;
        this.tasks = tasks; this.outbox = outbox; policies = clients.clone().baseUrl(policyUrl).build();
    }

    public Context context(UUID caseId, String stepId, String tenant) {
        return buildContext(caseId, stepId, tenant, completions.caseContext(caseId, tenant));
    }

    private Context buildContext(UUID caseId, String stepId, String tenant, JsonNode current) {
        if (!caseId.toString().equals(current.path("caseId").asText()) || !tenant.equals(current.path("tenantId").asText())) fail(404, "Case not found");
        var playbook = playbooks.get(caseId);
        if (!"OPEN".equals(current.path("status").asText()) || !"OPEN".equals(playbook.status())) fail(409, "Case or playbook is closed");
        if (!strings(current.path("requiredStepIds")).equals(playbook.requiredStepIds())
                || !strings(current.path("optionalStepIds")).equals(playbook.optionalStepIds())) fail(409, "Playbook reconciliation pending");
        if (!playbook.requiredStepIds().contains(stepId) && !playbook.optionalStepIds().contains(stepId)) fail(404, "Step not selected");
        JsonNode policy = policies.get().uri("/v1/policies/{id}/versions/{version}?tenantId={tenant}",
                current.path("policyId").asText(), current.path("policyVersion").asInt(), tenant).retrieve().body(JsonNode.class);
        if (policy == null) fail(409, "Pinned policy unavailable");
        JsonNode spec = null;
        for (JsonNode item : policy.path("steps")) if (stepId.equals(item.path("id").asText())) spec = item;
        if (spec == null || !"AGENT_THEN_HUMAN".equals(spec.path("owner").asText())
                || !"BANK_DOCUMENT_REVIEW".equals(spec.path("agent").path("type").asText())
                || !spec.path("agent").path("requireHumanReview").asBoolean()) fail(422, "Step does not authorize the bank document review agent with human review");
        if (strings(spec.path("completionEvidence")).isEmpty()) fail(422, "An agent review step must declare its evidence requirements");
        String currentStage = current.path("reviewStage").asText();
        if (currentStage.isBlank()) currentStage = policy.path("reviewStages").get(0).path("id").asText();
        boolean reviewPermitted = false;
        for (JsonNode stage : policy.path("reviewStages")) if (currentStage.equals(stage.path("id").asText())) {
            var actions = strings(stage.path("permittedActions"));
            reviewPermitted = actions.contains("COMPLETE_STEP") || actions.contains("RETURN_FOR_REWORK");
        }
        String planItem = playbooks.selectedPlanItem(playbook, stepId);
        Task task = tasks.createTaskQuery().caseInstanceId(playbook.flowableCaseInstanceId()).list().stream()
                .filter(item -> planItem.equals(item.getSubScopeId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Human review task is not active"));
        var documents = completions.agentDocuments(caseId);
        if (documents.isEmpty()) fail(422, "No supplied documents are registered for this case");
        UUID pending = jdbc.sql("select run_id from investigation_playbook.agent_submission where task_id = :task and status = 'AWAITING_REVIEW'")
                .param("task", task.getId()).query(UUID.class).optional().orElse(null);
        List<String> feedback = jdbc.sql("""
                select r.receipt->>'reason' from investigation_playbook.agent_review r
                join investigation_playbook.agent_submission s on s.run_id = r.run_id
                where s.case_id = :id and s.step_id = :step and r.receipt->>'action' = 'RETURN_FOR_REWORK'
                order by r.reviewed_at desc limit 5
                """).param("id", caseId).param("step", stepId).query(String.class).list();
        return new Context(caseId, tenant, stepId, task.getId(), current.path("revision").asLong(),
                playbook.playbookRevision(), current.path("policyId").asText(), current.path("policyVersion").asInt(),
                spec, documents, feedback, pending, reviewPermitted);
    }

    @Transactional
    public SubmissionView submit(UUID caseId, String stepId, ProposalEnvelope envelope, String tenant,
            String actor, String correlation) {
        if (!AGENT_ACTOR.equals(actor)) fail(403, "Agent findings must identify the bank document review agent");
        if (envelope == null || envelope.submission() == null || envelope.currentCase() == null) fail(400, "Submission required");
        Proposal proposal = envelope.submission();
        if (proposal.runId() == null || blank(proposal.findings()) || proposal.findings().length() < 80 || proposal.findings().length() > 100_000
                || proposal.citations() == null || proposal.gaps() == null || proposal.gaps().stream().anyMatch(AgentReviewService::blank)
                || blank(proposal.model()) || blank(proposal.promptVersion()) || blank(proposal.modelProvider()) || blank(proposal.recommendation())
                || !List.of("OLLAMA", "TEST_FIXTURE").contains(proposal.modelProvider())
                || !List.of("READY_FOR_REVIEW", "NEEDS_INFORMATION").contains(proposal.recommendation())) fail(422, "Invalid agent proposal");
        verifyTenant(envelope.currentCase(), caseId, tenant);
        playbooks.lockCase(caseId);
        Stored existing = stored(proposal.runId());
        if (existing != null) {
            if (!existing.caseId().equals(caseId) || !existing.stepId().equals(stepId)
                    || !tree(proposal).equals(json.readTree(existing.raw()))) fail(409, "Agent run ID reused with different findings");
            return view(existing);
        }
        Context context = buildContext(caseId, stepId, tenant, envelope.currentCase());
        if (context.caseRevision() != proposal.expectedCaseRevision()
                || context.playbookRevision() != proposal.expectedPlaybookRevision()
                || !context.taskId().equals(proposal.taskId())) fail(409, "Agent context is stale");
        if (context.pendingRunId() != null) fail(409, "This task already has agent findings awaiting review");
        var citations = completions.validateCitations(caseId, tenant, proposal.citations());
        Set<String> supplied = new HashSet<>();
        citations.forEach(citation -> supplied.add(citation.evidenceType()));
        if ("READY_FOR_REVIEW".equals(proposal.recommendation()) && (!proposal.gaps().isEmpty()
                || !supplied.containsAll(strings(context.step().path("completionEvidence"))))) fail(422, "Ready findings must cover required evidence and have no gaps");
        if ("NEEDS_INFORMATION".equals(proposal.recommendation()) && proposal.gaps().isEmpty()) fail(422, "Missing information must be described");
        try {
            jdbc.sql("""
                    insert into investigation_playbook.agent_submission
                    (run_id, case_id, step_id, task_id, case_revision, playbook_revision, status, submission, verified_citations)
                    values (:run, :caseId, :step, :task, :caseRevision, :playbookRevision, 'AWAITING_REVIEW', cast(:submission as jsonb), cast(:citations as jsonb))
                    """).param("run", proposal.runId()).param("caseId", caseId).param("step", stepId)
                    .param("task", context.taskId()).param("caseRevision", context.caseRevision())
                    .param("playbookRevision", context.playbookRevision()).param("submission", json.writeValueAsString(proposal))
                    .param("citations", json.writeValueAsString(citations)).update();
        } catch (DuplicateKeyException error) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent findings already submitted", error); }
        outbox.append("AgentFindingsSubmitted", tenant, caseId.toString(), context.playbookRevision(), correlation, actor,
                Map.of("runId", proposal.runId(), "stepId", stepId, "caseRevision", context.caseRevision()));
        return view(stored(proposal.runId()));
    }

    @Transactional
    public ReviewReceipt review(UUID caseId, String stepId, ReviewEnvelope envelope, String tenant,
            String actor, String correlation) {
        if (blank(actor) || actor.startsWith("agent:")) fail(403, "A human reviewer is required");
        if (envelope == null || envelope.submission() == null || envelope.currentCase() == null) fail(400, "Review required");
        Review command = envelope.submission();
        if (command.commandId() == null || command.runId() == null || blank(command.reason()) || blank(command.action())
                || !List.of("ACCEPT", "RETURN_FOR_REWORK").contains(command.action())) fail(400, "Invalid review command");
        verifyTenant(envelope.currentCase(), caseId, tenant);
        playbooks.lockCase(caseId);
        var prior = jdbc.sql("select actor_id, request::text, receipt::text from investigation_playbook.agent_review where command_id = :id")
                .param("id", command.commandId()).query((rs, row) -> new ReviewStored(rs.getString("actor_id"), rs.getString("request"), rs.getString("receipt")))
                .optional().orElse(null);
        if (prior != null) {
            ReviewReceipt receipt = json.readValue(prior.receipt(), ReviewReceipt.class);
            if (!prior.actor().equals(actor) || !receipt.caseId().equals(caseId) || !receipt.stepId().equals(stepId)
                    || !tree(command).equals(json.readTree(prior.request()))) fail(409, "Review command ID reused");
            return receipt;
        }
        Stored stored = stored(command.runId());
        if (stored == null || !stored.caseId().equals(caseId) || !stored.stepId().equals(stepId)) fail(404, "Agent run not found");
        if (!"AWAITING_REVIEW".equals(stored.status())) fail(409, "Agent findings were already reviewed");
        Context context = buildContext(caseId, stepId, tenant, envelope.currentCase());
        if (context.caseRevision() != command.expectedCaseRevision()
                || context.playbookRevision() != command.expectedPlaybookRevision()) fail(409, "Review context is stale");
        if (!context.humanReviewPermitted()) fail(422, "Current review stage does not permit agent review");
        Proposal proposal = json.readValue(stored.raw(), Proposal.class);
        TaskCompletionService.Receipt completion = null;
        if ("ACCEPT".equals(command.action())) {
            if (proposal.expectedCaseRevision() != context.caseRevision()
                    || proposal.expectedPlaybookRevision() != context.playbookRevision()
                    || !"READY_FOR_REVIEW".equals(proposal.recommendation()) || !proposal.gaps().isEmpty()) fail(409, "Findings require rework or a fresh agent run before acceptance");
            var submission = new TaskCompletionService.Command(command.commandId(), command.expectedCaseRevision(),
                    command.expectedPlaybookRevision(), proposal.taskId(), proposal.findings(), proposal.citations());
            completion = completions.completeReviewed(caseId, stepId,
                    new TaskCompletionService.Envelope(envelope.currentCase(), submission), tenant, actor, correlation, proposal.runId());
        } else {
            Task task = tasks.createTaskQuery().taskId(proposal.taskId()).singleResult();
            if (task != null && task.getAssignee() != null && !actor.equals(task.getAssignee())) fail(403, "Task assigned to another reviewer");
        }
        String status = completion == null ? "RETURNED" : "ACCEPTED";
        jdbc.sql("update investigation_playbook.agent_submission set status = :status where run_id = :run")
                .param("status", status).param("run", command.runId()).update();
        ReviewReceipt receipt = new ReviewReceipt(command.commandId(), command.runId(), caseId, stepId,
                command.action(), command.reason(), actor, status, completion, OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.sql("""
                insert into investigation_playbook.agent_review(command_id, run_id, actor_id, request, receipt)
                values (:id, :run, :actor, cast(:request as jsonb), cast(:receipt as jsonb))
                """).param("id", command.commandId()).param("run", command.runId()).param("actor", actor)
                .param("request", json.writeValueAsString(command)).param("receipt", json.writeValueAsString(receipt)).update();
        outbox.append("ACCEPTED".equals(status) ? "AgentFindingsAccepted" : "AgentFindingsReturned", tenant,
                caseId.toString(), completion == null ? context.playbookRevision() : completion.playbookRevision(), correlation, actor, receipt);
        return receipt;
    }

    public List<SubmissionView> list(UUID caseId, String stepId, String tenant) {
        completions.caseContext(caseId, tenant);
        return jdbc.sql("select run_id from investigation_playbook.agent_submission where case_id = :caseId and step_id = :step order by created_at, run_id")
                .param("caseId", caseId).param("step", stepId).query(UUID.class).list().stream().map(this::stored).map(this::view).toList();
    }

    private Stored stored(UUID id) {
        return jdbc.sql("select run_id, case_id, step_id, status, submission::text, verified_citations::text, created_at from investigation_playbook.agent_submission where run_id = :id")
                .param("id", id).query((rs, row) -> new Stored(id, rs.getObject("case_id", UUID.class), rs.getString("step_id"),
                        rs.getString("status"), rs.getString("submission"), rs.getString("verified_citations"), rs.getObject("created_at", OffsetDateTime.class)))
                .optional().orElse(null);
    }
    private SubmissionView view(Stored item) {
        return new SubmissionView(item.id(), item.caseId(), item.stepId(), item.status(), json.readValue(item.raw(), Proposal.class),
                Arrays.asList(json.readValue(item.citations(), TaskCompletionService.VerifiedCitation[].class)), item.at());
    }
    private JsonNode tree(Object value) { return json.readTree(json.writeValueAsString(value)); }
    private static void verifyTenant(JsonNode current, UUID caseId, String tenant) {
        if (!caseId.toString().equals(current.path("caseId").asText()) || !tenant.equals(current.path("tenantId").asText())) fail(404, "Case not found");
    }
    private static List<String> strings(JsonNode values) { List<String> list = new ArrayList<>(); for (JsonNode value : values) list.add(value.asText()); return list; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void fail(int code, String message) { throw new ResponseStatusException(HttpStatus.valueOf(code), message); }
    public record Context(UUID caseId, String tenantId, String stepId, String taskId, long caseRevision,
            long playbookRevision, String policyId, int policyVersion, JsonNode step,
            List<TaskCompletionService.AgentDocument> documents, List<String> reviewerFeedback, UUID pendingRunId,
            boolean humanReviewPermitted) {}
    public record Proposal(UUID runId, long expectedCaseRevision, long expectedPlaybookRevision, String taskId,
            String modelProvider, String model, String promptVersion, String findings,
            List<TaskCompletionService.Citation> citations, List<String> gaps, String recommendation) {}
    public record ProposalEnvelope(JsonNode currentCase, Proposal submission) {}
    public record Review(UUID commandId, UUID runId, String action, String reason, long expectedCaseRevision, long expectedPlaybookRevision) {}
    public record ReviewEnvelope(JsonNode currentCase, Review submission) {}
    public record SubmissionView(UUID runId, UUID caseId, String stepId, String status, Proposal proposal,
            List<TaskCompletionService.VerifiedCitation> verifiedCitations, OffsetDateTime submittedAt) {}
    public record ReviewReceipt(UUID commandId, UUID runId, UUID caseId, String stepId, String action, String reason,
            String reviewerId, String status, TaskCompletionService.Receipt completion, OffsetDateTime reviewedAt) {}
    private record Stored(UUID id, UUID caseId, String stepId, String status, String raw, String citations, OffsetDateTime at) {}
    private record ReviewStored(String actor, String request, String receipt) {}
}
