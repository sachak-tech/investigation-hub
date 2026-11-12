package com.investigationhub.policy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/policies")
@Tag(name = "Policy")
public class PolicyController {
    private final PolicyService policies;
    private final boolean demoPolicyAdminEnabled;

    public PolicyController(PolicyService policies,
            @Value("${ih.demo.policy-admin-enabled:false}") boolean demoPolicyAdminEnabled) {
        this.policies = policies;
        this.demoPolicyAdminEnabled = demoPolicyAdminEnabled;
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

    @PostMapping("/drafts/simulate")
    @Operation(summary = "Validate and simulate a draft policy without publishing it")
    public PolicyService.Evaluation simulate(@RequestBody PolicyService.SimulationRequest request) {
        requireDemoAdmin();
        return policies.simulate(request);
    }

    @PostMapping("/drafts")
    @Operation(summary = "Save an immutable draft policy version")
    public PolicyService.DraftView draft(@RequestBody JsonNode document) {
        requireDemoAdmin();
        return policies.saveDraft(document);
    }

    @PostMapping("/drafts/{policyId}/versions/{version}/publish")
    @Operation(summary = "Publish a validated draft for a synthetic development tenant")
    public PolicyService.DraftView publish(@RequestParam String tenantId, @PathVariable String policyId,
            @PathVariable int version) {
        requireDemoAdmin();
        return policies.publish(tenantId, policyId, version);
    }

    private void requireDemoAdmin() {
        if (!demoPolicyAdminEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft policy endpoints are disabled");
        }
    }

    @GetMapping("/grouping")
    @Operation(summary = "Read the published policy's case grouping window and version")
    public PolicyService.Grouping grouping(@RequestParam String tenantId) {
        return policies.grouping(tenantId);
    }
}
