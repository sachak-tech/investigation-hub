package com.investigationhub.playbook;

import com.investigationhub.common.OutboxWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.cmmn.api.CmmnHistoryService;
import org.flowable.cmmn.api.CmmnRuntimeService;
import org.flowable.cmmn.api.CmmnRepositoryService;
import org.flowable.cmmn.api.CmmnTaskService;
import org.flowable.cmmn.api.DynamicCmmnService;
import org.flowable.cmmn.api.history.HistoricPlanItemInstance;
import org.flowable.cmmn.api.runtime.CaseInstance;
import org.flowable.cmmn.api.runtime.PlanItemInstance;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PlaybookService {
    private static final Set<String> SUPPORTED_STEPS = Set.of(
            "transaction_review", "agent_transaction_analysis", "source_of_funds_review", "combined_risk_review",
            "sanctions_match_review", "agent_sanctions_enrichment", "pep_adverse_media_review", "cross_risk_assessment");
    private static final Map<String, String> STEP_DEFINITIONS = Map.of(
            "transaction_review", "transactionReviewTask",
            "agent_transaction_analysis", "agentAnalysisTask",
            "source_of_funds_review", "kycReviewTask",
            "combined_risk_review", "combinedReviewTask",
            "sanctions_match_review", "sanctionsReviewTask",
            "agent_sanctions_enrichment", "sanctionsEnrichmentTask",
            "pep_adverse_media_review", "pepReviewTask",
            "cross_risk_assessment", "crossRiskTask");
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final CmmnRuntimeService runtime;
    private final CmmnTaskService tasks;
    private final CmmnHistoryService history;
    private final CmmnRepositoryService repository;
    private final DynamicCmmnService dynamic;
    private final RestClient policyClient;

    public PlaybookService(JdbcClient jdbc, OutboxWriter outbox, JsonMapper json, CmmnRuntimeService runtime,
            CmmnTaskService tasks, CmmnHistoryService history, CmmnRepositoryService repository,
            DynamicCmmnService dynamic, RestClient.Builder clients,
            @Value("${ih.policy.base-url}") String policyUrl) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
        this.runtime = runtime;
        this.tasks = tasks;
        this.history = history;
        this.repository = repository;
        this.dynamic = dynamic;
        this.policyClient = clients.clone().baseUrl(policyUrl).build();
    }

    @Transactional
    public PlaybookView reconcile(UUID caseId, CaseSnapshot snapshot, UUID sourceEventId, String correlationId) {
        if (!caseId.equals(snapshot.caseId()) || !"OPEN".equals(snapshot.status())
                || snapshot.requiredStepIds() == null || snapshot.optionalStepIds() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported case snapshot or step definition");
        }
        Map<String, StepSpec> stepSpecs = policySteps(snapshot);
        List<String> selectedSteps = new ArrayList<>(snapshot.requiredStepIds());
        selectedSteps.addAll(snapshot.optionalStepIds());
        if (selectedSteps.size() != Set.copyOf(selectedSteps).size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Duplicate selected step");
        }
        for (String stepId : selectedSteps) {
            StepSpec spec = stepSpecs.get(stepId);
            if (spec == null || (!SUPPORTED_STEPS.contains(stepId)
                    && !List.of("HUMAN", "AGENT_THEN_HUMAN").contains(spec.owner()))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Unknown step or dynamic step owner is not HUMAN: " + stepId);
            }
        }
        jdbc.sql("insert into investigation_playbook.playbook_case_lock(case_id) values (:caseId) on conflict do nothing")
                .param("caseId", caseId).update();
        jdbc.sql("select case_id from investigation_playbook.playbook_case_lock where case_id = :caseId for update")
                .param("caseId", caseId).query(UUID.class).single();
        Mapping existing = mapping(caseId);
        if (existing != null && existing.caseRevision() >= snapshot.revision()) return get(caseId);
        if (existing != null && "CLOSED".equals(existing.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Playbook is closed");
        }

        Map<String, Object> variables = requiredStepVariables(selectedSteps);
        variables.put("kycAlert", snapshot.alertTypes().contains("KYC_SOURCE_OF_FUNDS"));
        variables.put("caseType", snapshot.caseType());
        long fromRevision = existing == null ? 0 : existing.playbookRevision();
        long toRevision = fromRevision + 1;
        List<String> before = existing == null ? List.of() : new ArrayList<>(parseSteps(existing.requiredSteps()));
        if (existing != null) before.addAll(parseSteps(existing.optionalSteps()));
        List<String> added = selectedSteps.stream().filter(step -> !before.contains(step)).toList();
        List<String> superseded = before.stream().filter(step -> !selectedSteps.contains(step)).toList();

        if (existing == null) {
            variables.put("productCaseId", caseId.toString());
            CaseInstance instance = runtime.createCaseInstanceBuilder().caseDefinitionKey("investigation")
                    .variables(variables).start();
            jdbc.sql("""
                    insert into investigation_playbook.playbook_instance
                      (case_id, tenant_id, flowable_case_instance_id, case_revision, playbook_revision,
                       case_type, policy_id, policy_version, required_steps, optional_steps, last_event_id)
                    values (:caseId, :tenantId, :flowableId, :caseRevision, :playbookRevision,
                            :caseType, :policyId, :policyVersion, cast(:requiredSteps as jsonb),
                            cast(:optionalSteps as jsonb), :eventId)
                    """)
                    .param("caseId", caseId).param("tenantId", snapshot.tenantId())
                    .param("flowableId", instance.getId()).param("caseRevision", snapshot.revision())
                    .param("playbookRevision", toRevision).param("caseType", snapshot.caseType())
                    .param("policyId", snapshot.policyId()).param("policyVersion", snapshot.policyVersion())
                    .param("requiredSteps", json.writeValueAsString(snapshot.requiredStepIds()))
                    .param("optionalSteps", json.writeValueAsString(snapshot.optionalStepIds()))
                    .param("eventId", sourceEventId).update();
        } else {
            if (!existing.tenantId().equals(snapshot.tenantId())
                    || !existing.policyId().equals(snapshot.policyId())
                    || existing.policyVersion() != snapshot.policyVersion()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant or pinned policy changed");
            }
            runtime.setVariables(existing.flowableId(), variables);
            jdbc.sql("""
                    update investigation_playbook.playbook_instance set case_revision = :caseRevision, playbook_revision = :playbookRevision,
                      case_type = :caseType, required_steps = cast(:requiredSteps as jsonb),
                      optional_steps = cast(:optionalSteps as jsonb),
                      last_event_id = :eventId, updated_at = now()
                    where case_id = :caseId
                    """)
                    .param("caseRevision", snapshot.revision()).param("playbookRevision", toRevision)
                    .param("caseType", snapshot.caseType())
                    .param("requiredSteps", json.writeValueAsString(snapshot.requiredStepIds()))
                    .param("optionalSteps", json.writeValueAsString(snapshot.optionalStepIds()))
                    .param("eventId", sourceEventId).param("caseId", caseId).update();
        }
        for (String stepId : added) {
            if (SUPPORTED_STEPS.contains(stepId)) continue;
            String flowableId = existing == null
                    ? mapping(caseId).flowableId() : existing.flowableId();
            String definitionId = repository.createCaseDefinitionQuery()
                    .caseDefinitionKey("extension").latestVersion().singleResult().getId();
            StepSpec spec = stepSpecs.get(stepId);
            PlanItemInstance injected = dynamic.createInjectedPlanItemInstanceBuilder()
                    .caseDefinitionId(definitionId).elementId("extraReviewItem")
                    .name(spec.name()).createInCase(flowableId);
            jdbc.sql("""
                    insert into investigation_playbook.dynamic_policy_step
                      (case_id, step_id, plan_item_instance_id, name, destination_id)
                    values (:caseId, :stepId, :planItemId, :name, :destinationId)
                    on conflict (case_id, step_id) do nothing
                    """).param("caseId", caseId).param("stepId", stepId)
                    .param("planItemId", injected.getId()).param("name", spec.name())
                    .param("destinationId", spec.destinationId()).update();
        }
        outbox.append("PlaybookRevised", snapshot.tenantId(), caseId.toString(), toRevision,
                correlationId, "playbook-service",
                Map.of("caseId", caseId.toString(), "fromRevision", fromRevision,
                        "toRevision", toRevision, "addedStepIds", added, "supersededStepIds", superseded,
                        "caseRevision", snapshot.revision(), "sourceEventId", sourceEventId.toString()));
        return get(caseId);
    }

    public PlaybookView get(UUID caseId) {
        Mapping mapping = mapping(caseId);
        if (mapping == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Playbook not created yet");
        boolean closed = "CLOSED".equals(mapping.status());
        boolean running = !closed && runtime.createCaseInstanceQuery().caseInstanceId(mapping.flowableId())
                .singleResult() != null;
        List<StepView> steps = !running ? history(caseId) : runtime.createPlanItemInstanceQuery()
                .caseInstanceId(mapping.flowableId()).list().stream().map(this::step).toList();
        List<TaskView> humanTasks = !running ? List.of() : tasks.createTaskQuery()
                .caseInstanceId(mapping.flowableId()).list().stream().map(this::task).toList();
        return new PlaybookView(caseId, mapping.flowableId(), mapping.caseRevision(),
                mapping.playbookRevision(), mapping.caseType(), mapping.lastEventId(),
                parseSteps(mapping.requiredSteps()), parseSteps(mapping.optionalSteps()),
                !running ? null : runtime.getVariable(mapping.flowableId(), "agentFinding"),
                steps, humanTasks, mapping.status(), dynamicSteps(caseId));
    }

    public Readiness readiness(UUID caseId) {
        Mapping current = mapping(caseId);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Playbook not created yet");
        List<StepView> observed = new ArrayList<>(get(caseId).steps());
        observed.addAll(history(caseId));
        List<String> pending = parseSteps(current.requiredSteps()).stream()
                .filter(stepId -> observed.stream().noneMatch(item ->
                        stepCompleted(caseId, stepId, item)))
                .toList();
        return new Readiness(caseId, current.caseRevision(), current.playbookRevision(),
                parseSteps(current.requiredSteps()), pending, pending.isEmpty());
    }

    @Transactional
    public PlaybookView close(UUID caseId, CloseCommand command, String correlationId) {
        lockCase(caseId);
        Mapping current = mapping(caseId);
        if (current == null || !current.tenantId().equals(command.tenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Playbook not found");
        }
        if ("CLOSED".equals(current.status())) return get(caseId);
        if (command.caseRevision() < current.caseRevision() || !readiness(caseId).ready()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Playbook is not ready to close");
        }
        if (runtime.createCaseInstanceQuery().caseInstanceId(current.flowableId()).singleResult() != null) {
            runtime.terminateCaseInstance(current.flowableId());
        }
        jdbc.sql("""
                update investigation_playbook.playbook_instance
                set status = 'CLOSED', closed_at = now(), case_revision = :revision, updated_at = now()
                where case_id = :caseId
                """).param("revision", command.caseRevision()).param("caseId", caseId).update();
        outbox.append("PlaybookClosed", current.tenantId(), caseId.toString(),
                current.playbookRevision(), correlationId, "playbook-service",
                Map.of("caseId", caseId.toString(), "caseRevision", command.caseRevision()));
        return get(caseId);
    }

    public List<StepView> history(UUID caseId) {
        Mapping mapping = mapping(caseId);
        if (mapping == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Playbook not created yet");
        return history.createHistoricPlanItemInstanceQuery()
                .planItemInstanceCaseInstanceId(mapping.flowableId()).list()
                .stream().map(this::step).toList();
    }

    @Transactional
    public PlaybookView completeDemoTask(UUID caseId, String taskId) {
        lockCase(caseId);
        Mapping current = mapping(caseId);
        if (current == null || !current.tenantId().startsWith("demo-")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo case not found");
        }
        Task task = tasks.createTaskQuery().caseInstanceId(current.flowableId()).taskId(taskId).singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active human task not found in this case");
        }
        tasks.complete(taskId);
        return get(caseId);
    }

    private Mapping mapping(UUID caseId) {
        return jdbc.sql("""
                select tenant_id, flowable_case_instance_id, case_revision, playbook_revision,
                       case_type, policy_id, policy_version, required_steps::text as required_steps,
                       optional_steps::text as optional_steps, last_event_id, status
                from investigation_playbook.playbook_instance where case_id = :caseId
                """).param("caseId", caseId)
                .query((rs, row) -> new Mapping(rs.getString("tenant_id"),
                        rs.getString("flowable_case_instance_id"), rs.getLong("case_revision"),
                        rs.getLong("playbook_revision"), rs.getString("case_type"),
                        rs.getString("policy_id"), rs.getInt("policy_version"),
                        rs.getString("required_steps"), rs.getString("optional_steps"),
                        rs.getObject("last_event_id", UUID.class),
                        rs.getString("status")))
                .optional().orElse(null);
    }

    void lockCase(UUID caseId) {
        jdbc.sql("select case_id from investigation_playbook.playbook_case_lock where case_id = :caseId for update")
                .param("caseId", caseId).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playbook not found"));
    }

    String selectedPlanItem(PlaybookView view, String stepId) {
        String dynamicId = view.dynamicSteps().stream().filter(step -> step.stepId().equals(stepId))
                .map(DynamicStep::planItemInstanceId).findFirst().orElse(null);
        return view.steps().stream().filter(item -> "active".equals(item.state()))
                .filter(item -> dynamicId == null ? item.definitionId().equals(STEP_DEFINITIONS.get(stepId))
                        : item.id().equals(dynamicId))
                .map(StepView::id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Selected step is not active"));
    }

    private List<String> parseSteps(String raw) {
        List<String> values = new ArrayList<>();
        for (JsonNode step : json.readTree(raw)) values.add(step.asText());
        return values;
    }

    private Map<String, Object> requiredStepVariables(List<String> stepIds) {
        Map<String, Object> variables = new HashMap<>();
        for (String stepId : SUPPORTED_STEPS) {
            variables.put(stepId + "Required", stepIds.contains(stepId));
        }
        return variables;
    }

    private Map<String, StepSpec> policySteps(CaseSnapshot snapshot) {
        JsonNode policy = policyClient.get().uri(
                "/v1/policies/{policyId}/versions/{version}?tenantId={tenantId}",
                snapshot.policyId(), snapshot.policyVersion(), snapshot.tenantId())
                .retrieve().body(JsonNode.class);
        if (policy == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned policy unavailable");
        Map<String, StepSpec> specs = new HashMap<>();
        for (JsonNode step : policy.path("steps")) {
            specs.put(step.path("id").asText(), new StepSpec(step.path("id").asText(),
                    step.path("name").asText(step.path("id").asText().replace('_', ' ')),
                    step.path("owner").asText(), step.path("destinationId").asText()));
        }
        return specs;
    }

    private List<DynamicStep> dynamicSteps(UUID caseId) {
        return jdbc.sql("""
                select step_id, plan_item_instance_id, name, destination_id
                from investigation_playbook.dynamic_policy_step where case_id = :caseId order by step_id
                """).param("caseId", caseId).query((rs, row) -> new DynamicStep(
                        rs.getString("step_id"), rs.getString("plan_item_instance_id"),
                        rs.getString("name"), rs.getString("destination_id"))).list();
    }

    private boolean stepCompleted(UUID caseId, String stepId, StepView item) {
        if (!"completed".equals(item.state())) return false;
        String builtIn = STEP_DEFINITIONS.get(stepId);
        if (builtIn != null) return builtIn.equals(item.definitionId());
        return dynamicSteps(caseId).stream().anyMatch(step -> step.stepId().equals(stepId)
                && step.planItemInstanceId().equals(item.id()));
    }

    private StepView step(PlanItemInstance item) {
        return new StepView(item.getId(), item.getName(), item.getState(), item.getPlanItemDefinitionId());
    }

    private StepView step(HistoricPlanItemInstance item) {
        return new StepView(item.getId(), item.getName(), item.getState(), item.getPlanItemDefinitionId());
    }

    private TaskView task(Task item) {
        return new TaskView(item.getId(), item.getName(), item.getAssignee());
    }

    private record Mapping(String tenantId, String flowableId, long caseRevision, long playbookRevision,
            String caseType, String policyId, int policyVersion, String requiredSteps,
            String optionalSteps, UUID lastEventId,
            String status) {}
    public record CaseSnapshot(UUID caseId, String tenantId, String subjectId, String caseType,
            String status, long revision, String policyId, int policyVersion, List<String> alertTypes,
            List<String> requiredStepIds, List<String> optionalStepIds) {}
    public record StepView(String id, String name, String state, String definitionId) {}
    public record TaskView(String id, String name, String assignee) {}
    public record PlaybookView(UUID caseId, String flowableCaseInstanceId, long caseRevision,
            long playbookRevision, String caseType, UUID lastEventId,
            List<String> requiredStepIds, List<String> optionalStepIds,
            Object agentFinding, List<StepView> steps, List<TaskView> humanTasks, String status,
            List<DynamicStep> dynamicSteps) {}
    public record DynamicStep(String stepId, String planItemInstanceId, String name, String destinationId) {}
    private record StepSpec(String id, String name, String owner, String destinationId) {}
    public record Readiness(UUID caseId, long caseRevision, long playbookRevision,
            List<String> requiredStepIds, List<String> pendingStepIds, boolean ready) {}
    public record CloseCommand(String tenantId, long caseRevision) {}
}
