package com.investigationhub.policy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/policies")
@Tag(name = "Policy")
public class PolicyController {
    private final PolicyService policies;

    public PolicyController(PolicyService policies) {
        this.policies = policies;
    }

    @GetMapping("/active")
    @Operation(summary = "Read the published policy for a tenant")
    public JsonNode active(@RequestParam String tenantId) {
        return policies.active(tenantId);
    }

    @GetMapping("/{policyId}/versions/{version}")
    @Operation(summary = "Read the exact policy version pinned by a case")
    public JsonNode version(@RequestParam String tenantId, @PathVariable String policyId, @PathVariable int version) {
        return policies.version(tenantId, policyId, version);
    }

    @PostMapping("/evaluate-case")
    @Operation(summary = "Evaluate classification and required steps for an alert set")
    public PolicyService.Evaluation evaluate(@RequestBody PolicyService.EvaluateCaseRequest request) {
        return policies.evaluate(request);
    }

    @GetMapping("/grouping")
    @Operation(summary = "Read the published policy's case grouping window and version")
    public PolicyService.Grouping grouping(@RequestParam String tenantId) {
        return policies.grouping(tenantId);
    }
}
