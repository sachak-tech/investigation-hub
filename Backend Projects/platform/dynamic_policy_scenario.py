#!/usr/bin/env python3
"""Publish a new policy version and execute a bank-defined human review step."""

import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

from smoke import URLS, complete_demo_task, eventually, request
from case_routing_scenarios import route, expect_failure


def alert(tenant, subject, run_id):
    return request("alert", "/v1/alerts", "POST", {
        "tenantId": tenant,
        "sourceSystem": "dynamic-policy-scenario",
        "sourceAlertId": "kyc-" + subject,
        "alertType": "KYC_SOURCE_OF_FUNDS",
        "subjectId": subject,
        "subjectVerified": True,
        "detectedAt": datetime.now(timezone.utc).isoformat(),
    }, "dynamic-policy-" + run_id)


def main():
    run_id = uuid.uuid4().hex[:10]
    tenant = "demo-dynamic-" + run_id
    fixtures = Path(__file__).resolve().parent / "examples"
    first = json.loads((fixtures / "dynamic-policy.v1.json").read_text())
    second = json.loads((fixtures / "dynamic-policy.v2.json").read_text())
    first["tenantId"] = second["tenantId"] = tenant

    simulated = request("policy", "/v1/policies/drafts/simulate", "POST", {
        "document": second, "alertTypes": ["KYC_SOURCE_OF_FUNDS"]})
    assert simulated["requiredStepIds"] == ["source_of_funds_review", "bank_document_review"]
    assert simulated["optionalStepIds"] == ["bank_extra_check"]
    invalid = json.loads(json.dumps(second))
    invalid["destinations"][0]["sectionId"] = "https://unapproved.example"
    expect_failure(422, lambda: request("policy", "/v1/policies/drafts/simulate", "POST",
                                       {"document": invalid, "alertTypes": ["KYC_SOURCE_OF_FUNDS"]}))
    assert request("policy", "/v1/policies/drafts", "POST", first)["status"] == "DRAFT"
    assert request("policy", "/v1/policies/drafts/dynamic-kyc-policy/versions/1/publish?tenantId="
                   + tenant, "POST")["status"] == "PUBLISHED"
    old_alert = alert(tenant, "old-" + run_id, run_id)
    old_case = eventually(
        lambda: request("case", "/v1/cases/by-alert/" + old_alert["alertId"]),
        lambda value: value["policyVersion"] == 1,
        "v1 case",
    )
    assert old_case["requiredStepIds"] == ["source_of_funds_review"]
    assert request("policy", "/v1/policies/drafts", "POST", second)["status"] == "DRAFT"
    assert request("policy", "/v1/policies/drafts/dynamic-kyc-policy/versions/2/publish?tenantId="
                   + tenant, "POST")["status"] == "PUBLISHED"
    assert request("policy", "/v1/policies/active?tenantId=" + tenant)["version"] == 2
    assert request("policy", "/v1/policies/dynamic-kyc-policy/versions/1?tenantId="
                   + tenant)["version"] == 1

    new_alert = alert(tenant, "new-" + run_id, run_id)
    case = eventually(
        lambda: request("case", "/v1/cases/by-alert/" + new_alert["alertId"]),
        lambda value: value["policyVersion"] == 2 and
        value["requiredStepIds"] == ["source_of_funds_review", "bank_document_review"],
        "v2 case with bank step",
    )
    case_id = case["caseId"]
    playbook = eventually(
        lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
        lambda value: value["caseRevision"] == 1 and len(value["dynamicSteps"]) == 2
        and len(value["humanTasks"]) == 3,
        "Flowable dynamic human task",
    )
    dynamic = next(item for item in playbook["dynamicSteps"]
                   if item["stepId"] == "bank_document_review")
    assert dynamic["stepId"] == "bank_document_review"
    assert playbook["optionalStepIds"] == ["bank_extra_check"]
    assert any(item["stepId"] == "bank_extra_check" for item in playbook["dynamicSteps"])
    assert dynamic["destinationId"] == "external_bank_documents"
    assert any(item["id"] == dynamic["planItemInstanceId"] and item["state"] == "active"
               for item in playbook["steps"])
    navigation = request("playbook", "/v1/playbooks/cases/" + case_id + "/navigation")
    assert len(navigation) == 3
    bank_nav = next(item for item in navigation if item["stepId"] == "bank_document_review")
    assert bank_nav["available"] and bank_nav["sectionId"] == "bank-documents"
    assert bank_nav["focusField"] == "documents" and bank_nav["policyVersion"] == 2
    assert bank_nav["context"]["customerId"] == "new-" + run_id
    assert bank_nav["context"]["caseId"] == case_id
    optional_nav = request("playbook", "/v1/playbooks/cases/" + case_id
                           + "/steps/bank_extra_check/navigation")
    assert not optional_nav["required"] and optional_nav["sectionId"] == "bank-documents"
    expect_failure(404, lambda: request("playbook", "/v1/playbooks/cases/" + case_id
                                       + "/steps/transaction_review/navigation"))
    pending = request("playbook", "/v1/playbooks/cases/" + case_id + "/readiness")
    assert "bank_document_review" in pending["pendingStepIds"]

    after_kyc = complete_demo_task(case_id, playbook, "Review source of funds")
    after_bank = complete_demo_task(case_id, after_kyc, "Review bank documents")
    ready = request("playbook", "/v1/playbooks/cases/" + case_id + "/readiness")
    assert ready["ready"] and not ready["pendingStepIds"]
    routed = route(case_id, case["revision"], "analyst-1", "ESCALATE", "KYC_APPROVER", "approver-2")
    closed = route(case_id, routed["revision"], "approver-2", "APPROVE_CLOSE")
    assert closed["status"] == "CLOSED"
    closed_playbook = eventually(lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
                                 lambda value: value["status"] == "CLOSED", "closed dynamic playbook")
    optional = next(item for item in closed_playbook["dynamicSteps"] if item["stepId"] == "bank_extra_check")
    assert any(item["id"] == optional["planItemInstanceId"] and item["state"] == "terminated"
               for item in closed_playbook["steps"])
    assert request("case", "/v1/cases/" + old_case["caseId"])["policyVersion"] == 1

    output = {"result": "PASS", "tenantId": tenant, "oldCaseId": old_case["caseId"],
              "newCaseId": case_id, "newPolicyVersion": 2,
              "dynamicStepId": dynamic["stepId"], "optionalStepId": "bank_extra_check",
              "destinationId": dynamic["destinationId"],
              "monitorUrl": URLS["playbook"] + "/playbook-monitor.html?caseId=" + case_id,
              "journeyUrl": URLS["case"] + "/case-journey.html?caseId=" + case_id}
    target = Path(__file__).resolve().parents[2] / ".cache" / "dynamic-policy-scenario.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
