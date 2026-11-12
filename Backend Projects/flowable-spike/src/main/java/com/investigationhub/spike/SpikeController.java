package com.investigationhub.spike;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.flowable.cmmn.api.CmmnManagementService;
import org.flowable.cmmn.api.CmmnHistoryService;
import org.flowable.cmmn.api.CmmnRepositoryService;
import org.flowable.cmmn.api.CmmnRuntimeService;
import org.flowable.cmmn.api.CmmnTaskService;
import org.flowable.cmmn.api.DynamicCmmnService;
import org.flowable.cmmn.api.runtime.CaseInstance;
import org.flowable.cmmn.api.runtime.PlanItemInstance;
import org.flowable.cmmn.api.history.HistoricPlanItemInstance;
import org.flowable.job.api.AcquiredExternalWorkerJob;
import org.flowable.task.api.Task;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/spike")
@Tag(name = "Flowable spike", description = "Synthetic case lifecycle, human tasks, and Python worker handoff")
public class SpikeController {
    private final CmmnRepositoryService repository;
    private final CmmnHistoryService history;
    private final CmmnRuntimeService runtime;
    private final CmmnTaskService tasks;
    private final CmmnManagementService management;
    private final DynamicCmmnService dynamic;

    public SpikeController(CmmnRepositoryService repository, CmmnHistoryService history, CmmnRuntimeService runtime,
            CmmnTaskService tasks, CmmnManagementService management, DynamicCmmnService dynamic) {
        this.repository = repository;
        this.history = history;
        this.runtime = runtime;
        this.tasks = tasks;
        this.management = management;
        this.dynamic = dynamic;
    }

    @Bean
    ApplicationRunner deploySpikeModels(CmmnRepositoryService repository) {
        return args -> {
            if (repository.createCaseDefinitionQuery().caseDefinitionKey("investigation").count() == 0) {
                repository.createDeployment().name("investigation-spike")
                        .addClasspathResource("cases/investigation.cmmn")
                        .addClasspathResource("cases/extension.cmmn")
                        .deploy();
            }
        };
    }

    public record StartCase(String caseId) {}
    public record CaseStarted(String productCaseId, String flowableCaseInstanceId) {}
    public record StepView(String id, String name, String state, String definitionId) {}
    public record TaskView(String id, String name, String assignee) {}
    public record CaseView(String caseInstanceId, Object caseType, Object agentFinding,
            List<StepView> steps, List<TaskView> humanTasks) {}
    public record JobView(String id) {}
    public record CompleteJob(String workerId, String finding) {}

    @PostMapping("/cases")
    @Operation(summary = "Start a synthetic transaction investigation case")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseStarted start(@RequestBody StartCase request) {
        CaseInstance instance = runtime.createCaseInstanceBuilder()
                .caseDefinitionKey("investigation")
                .variable("productCaseId", request.caseId())
                .variable("caseType", "TRANSACTION_REVIEW")
                .variable("kycAlert", false)
                .start();
        return new CaseStarted(request.caseId(), instance.getId());
    }

    @GetMapping("/cases/{instanceId}")
    @Operation(summary = "Inspect active case steps and human tasks")
    public CaseView get(@PathVariable String instanceId) {
        List<StepView> steps = runtime.createPlanItemInstanceQuery().caseInstanceId(instanceId).list()
                .stream().map(this::stepView).toList();
        List<TaskView> humanTasks = tasks.createTaskQuery().caseInstanceId(instanceId).list()
                .stream().map(this::taskView).toList();
        return new CaseView(instanceId, runtime.getVariable(instanceId, "caseType"),
                runtime.getVariable(instanceId, "agentFinding"), steps, humanTasks);
    }

    @GetMapping("/cases/{instanceId}/history")
    @Operation(summary = "Inspect retained Flowable plan-item history")
    public List<StepView> history(@PathVariable String instanceId) {
        return history.createHistoricPlanItemInstanceQuery()
                .planItemInstanceCaseInstanceId(instanceId).list()
                .stream().map(this::historicStepView).toList();
    }

    @PostMapping("/cases/{instanceId}/kyc-alert")
    @Operation(summary = "Add a synthetic KYC alert and activate its review step")
    public CaseView addKycAlert(@PathVariable String instanceId) {
        runtime.setVariables(instanceId, Map.of("kycAlert", true, "caseType", "TRANSACTION_AND_KYC_REVIEW"));
        return get(instanceId);
    }

    @PostMapping("/cases/{instanceId}/extra-step")
    @Operation(summary = "Inject an additional review step into an active case")
    public CaseView injectExtraStep(@PathVariable String instanceId) {
        String definitionId = repository.createCaseDefinitionQuery()
                .caseDefinitionKey("extension").latestVersion().singleResult().getId();
        dynamic.createInjectedPlanItemInstanceBuilder()
                .caseDefinitionId(definitionId)
                .elementId("extraReviewItem")
                .name("Review external source")
                .createInCase(instanceId);
        return get(instanceId);
    }

    @PostMapping("/tasks/{taskId}/complete")
    @Operation(summary = "Complete a human review task")
    public void completeHumanTask(@PathVariable String taskId) {
        tasks.complete(taskId);
    }

    @GetMapping("/workers/acquire")
    @Operation(summary = "Acquire a Flowable external-worker job")
    public List<JobView> acquire(@RequestParam(defaultValue = "synthetic-python-worker") String workerId) {
        return management.createExternalWorkerJobAcquireBuilder()
                .topic("transaction-analysis", Duration.ofMinutes(5))
                .acquireAndLock(1, workerId)
                .stream().map(job -> new JobView(job.getId())).toList();
    }

    @PostMapping("/workers/{jobId}/complete")
    @Operation(summary = "Complete a Python agent external-worker job")
    public void completeJob(@PathVariable String jobId, @RequestBody CompleteJob request) {
        management.createCmmnExternalWorkerTransitionBuilder(jobId, request.workerId())
                .variable("agentFinding", request.finding())
                .complete();
    }

    private StepView stepView(PlanItemInstance item) {
        return new StepView(item.getId(), item.getName(), item.getState(), item.getPlanItemDefinitionId());
    }

    private StepView historicStepView(HistoricPlanItemInstance item) {
        return new StepView(item.getId(), item.getName(), item.getState(), item.getPlanItemDefinitionId());
    }

    private TaskView taskView(Task task) {
        return new TaskView(task.getId(), task.getName(), task.getAssignee());
    }
}

