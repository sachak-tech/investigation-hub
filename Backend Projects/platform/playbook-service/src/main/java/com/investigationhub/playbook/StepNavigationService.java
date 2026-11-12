package com.investigationhub.playbook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class StepNavigationService {
    private static final Map<String, String> BUILT_INS = Map.ofEntries(
            Map.entry("transaction_timeline", "transactions"), Map.entry("agent_findings", "agent-findings"),
            Map.entry("bank_source_of_funds", "source-of-funds"), Map.entry("source_of_funds", "source-of-funds"),
            Map.entry("combined_risk", "combined-risk"), Map.entry("screening_matches", "screening-matches"),
            Map.entry("pep_adverse_media", "pep-adverse-media"), Map.entry("cross_risk", "cross-risk"));
    private final PlaybookService playbooks;
    private final RestClient cases;
    private final RestClient policies;

    public StepNavigationService(PlaybookService playbooks, RestClient.Builder clients,
            @Value("${ih.case.base-url}") String caseUrl,
            @Value("${ih.policy.base-url}") String policyUrl) {
        this.playbooks = playbooks;
        this.cases = clients.clone().baseUrl(caseUrl).build();
        this.policies = clients.clone().baseUrl(policyUrl).build();
    }

    public List<Navigation> list(UUID caseId) {
        JsonNode context = cases.get().uri("/v1/cases/{caseId}", caseId).retrieve().body(JsonNode.class);
        if (context == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Case context unavailable");
        PlaybookService.PlaybookView playbook = playbooks.get(caseId);
        List<String> required = strings(context.path("requiredStepIds"));
        List<String> optional = strings(context.path("optionalStepIds"));
        if (!required.equals(playbook.requiredStepIds()) || !optional.equals(playbook.optionalStepIds())
                || !context.path("caseType").asText().equals(playbook.caseType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Playbook navigation is awaiting case reconciliation");
        }
        JsonNode policy = policies.get().uri("/v1/policies/{policyId}/versions/{version}?tenantId={tenantId}",
                context.path("policyId").asText(), context.path("policyVersion").asInt(),
                context.path("tenantId").asText()).retrieve().body(JsonNode.class);
        if (policy == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned policy unavailable");
        Map<String, JsonNode> destinations = new HashMap<>();
        for (JsonNode destination : policy.path("destinations")) {
            destinations.put(destination.path("destinationId").asText(), destination);
        }
        List<Navigation> result = new ArrayList<>();
        for (JsonNode step : policy.path("steps")) {
            String stepId = step.path("id").asText();
            if (!required.contains(stepId) && !optional.contains(stepId)) continue;
            String destinationId = step.path("destinationId").asText();
            JsonNode destination = destinations.get(destinationId);
            String kind = destination == null ? "BUILT_IN" : destination.path("kind").asText();
            String section = destination == null ? BUILT_INS.get(destinationId)
                    : destination.path("sectionId").asText();
            String focus = destination == null ? null : value(destination, "focusField");
            String externalRef = destination == null ? null : value(destination, "externalSectionRef");
            Map<String, String> resolved = new LinkedHashMap<>();
            resolved.put("caseId", caseId.toString());
            resolved.put("subjectId", context.path("subjectId").asText());
            resolved.put("stepId", stepId);
            resolved.put("tenantId", context.path("tenantId").asText());
            if (destination != null) {
                for (var entry : destination.path("contextMapping").properties()) {
                    String source = entry.getValue().asText();
                    String mapped = switch (source) {
                        case "case.id" -> caseId.toString();
                        case "case.primarySubjectId" -> context.path("subjectId").asText();
                        case "case.tenantId" -> context.path("tenantId").asText();
                        case "step.id" -> stepId;
                        default -> null;
                    };
                    if (mapped == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Unsupported destination context source: " + source);
                    resolved.put(entry.getKey(), mapped);
                }
            }
            boolean available = "BUILT_IN".equals(kind) && section != null && !section.isBlank();
            String reason = available ? null : "EXTERNAL_SECTION".equals(kind)
                    ? "External sections are reserved for a future implementation" : "Destination is not configured";
            String route = available ? "/cases/" + caseId + "/investigation?stepId=" + stepId
                    + "&section=" + section + (focus == null ? "" : "#" + focus) : null;
            result.add(new Navigation(caseId, context.path("revision").asLong(),
                    context.path("policyId").asText(), context.path("policyVersion").asInt(), stepId,
                    step.path("name").asText(stepId.replace('_', ' ')), required.contains(stepId),
                    destinationId, kind, "CASE_INVESTIGATION", section, focus, externalRef, resolved,
                    strings(step.path("dataPoints")), strings(step.path("completionEvidence")),
                    available, reason, route, value(step.path("agent"), "type"),
                    step.path("agent").path("requireHumanReview").asBoolean()));
        }
        return result;
    }

    public Navigation get(UUID caseId, String stepId) {
        return list(caseId).stream().filter(item -> item.stepId().equals(stepId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Step is not selected for this case"));
    }

    private static String value(JsonNode node, String name) {
        return node.path(name).isMissingNode() || node.path(name).isNull() ? null : node.path(name).asText();
    }
    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(item.asText());
        return values;
    }
    public record Navigation(UUID caseId, long caseRevision, String policyId, int policyVersion,
            String stepId, String name, boolean required, String destinationId, String kind,
            String workspace, String sectionId, String focusField, String externalSectionRef,
            Map<String, String> context, List<String> dataPoints, List<String> completionEvidence,
            boolean available, String unavailableReason, String workspaceRoute,
            String agentType, boolean humanReviewRequired) {}
}
