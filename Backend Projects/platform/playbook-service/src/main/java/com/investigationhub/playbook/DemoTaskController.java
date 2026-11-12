package com.investigationhub.playbook;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/playbooks/demo/cases")
@ConditionalOnProperty(name = "ih.demo.task-completion-enabled", havingValue = "true")
public class DemoTaskController {
    private final PlaybookService playbooks;

    public DemoTaskController(PlaybookService playbooks) {
        this.playbooks = playbooks;
    }

    @PostMapping("/{caseId}/tasks/{taskId}/complete")
    @Operation(summary = "Complete an active human task in a synthetic demo case")
    public PlaybookService.PlaybookView complete(@PathVariable UUID caseId, @PathVariable String taskId) {
        return playbooks.completeDemoTask(caseId, taskId);
    }
}
