#!/usr/bin/env python3
"""Run a real local Ollama document review, human return/rework and acceptance."""
import copy
import json
import os
from pathlib import Path
import uuid
from smoke import request, eventually, URLS
from task_completion_scenario import accept, get_playbook, req
from case_routing_scenarios import expect_failure

URLS["agent"] = os.getenv("IH_AGENT_URL", "http://127.0.0.1:18085")
ACTOR = "investigator-1"
FULL_TEXT = "\n".join([
    "Synthetic bank statement for local testing only.",
    "Account holder: Synthetic Customer 001.",
    "Statement period: October 2026.",
    "Employer: Example Employer.",
    "Salary credit: 5000 synthetic units on 2026-10-31.",
    "Declared source of funds: salary.",
    "This supplied synthetic document has not been independently authenticated."])


def prepare(tenant, text, subject):
    alert = accept(tenant, subject, "KYC_SOURCE_OF_FUNDS")
    case = eventually(lambda: request("case", "/v1/cases/by-alert/" + alert["alertId"]), lambda value: True, "agent case")
    case_id = case["caseId"]
    view = eventually(lambda: get_playbook(case_id), lambda value: len(value["humanTasks"]) == 3, "agent review tasks")
    document = req("playbook", "/v1/playbooks/cases/" + case_id + "/documents", tenant, "POST", {
        "documentId": str(uuid.uuid4()), "title": "Supplied synthetic source",
        "sourceReference": "supplied://agent-scenario-source", "content": text})
    return case, view, document


def run_agent(case, view, tenant):
    body = {"runId": str(uuid.uuid4()), "caseId": case["caseId"], "stepId": "bank_document_review",
            "expectedCaseRevision": case["revision"], "expectedPlaybookRevision": view["playbookRevision"]}
    run = req("agent", "/v1/agent-runs", tenant, "POST", body)
    assert run["modelProvider"] == "OLLAMA" and run["modelDigest"]
    assert req("agent", "/v1/agent-runs", tenant, "POST", body)["runId"] == run["runId"]
    expect_failure(409, lambda: req("agent", "/v1/agent-runs", tenant, "POST", dict(body, expectedCaseRevision=999)))
    expect_failure(404, lambda: req("agent", "/v1/agent-runs/" + body["runId"], "different-tenant"))
    run = eventually(lambda: req("agent", "/v1/agent-runs/" + body["runId"], tenant),
                     lambda value: value["status"] in ("AWAITING_REVIEW", "FAILED"), "local model agent findings", timeout=300)
    assert run["status"] == "AWAITING_REVIEW", run
    submission = run["result"]["submission"]
    assert submission["proposal"]["modelProvider"] == "OLLAMA"
    assert submission["status"] == "AWAITING_REVIEW"
    assert any(task["name"] == "Review bank documents" for task in get_playbook(case["caseId"])["humanTasks"])
    return run, submission


def review(case, view, run_id, action, tenant, reason):
    body = {"commandId": str(uuid.uuid4()), "runId": run_id, "action": action, "reason": reason,
            "expectedCaseRevision": case["revision"], "expectedPlaybookRevision": view["playbookRevision"]}
    path = "/v1/cases/" + case["caseId"] + "/steps/bank_document_review/agent-review"
    return body, path


def main():
    health = eventually(lambda: request("agent", "/actuator/health/readiness"), lambda value: value["status"] == "UP", "agent service readiness", timeout=40)
    assert health["modelAvailable"], "Install the configured local model first"
    run_id = uuid.uuid4().hex[:10]
    tenant = "demo-agent-" + run_id
    policy = json.loads((Path(__file__).parent / "examples/bank-document-agent.v1.json").read_text())
    policy["tenantId"] = tenant
    request("policy", "/v1/policies/drafts", "POST", policy)
    request("policy", "/v1/policies/drafts/bank-document-agent-policy/versions/1/publish?tenantId=" + tenant, "POST")
    case, view, document = prepare(tenant, FULL_TEXT, "complete-" + run_id)
    first, findings = run_agent(case, view, tenant)
    proposal = findings["proposal"]
    assert proposal["recommendation"] == "READY_FOR_REVIEW", proposal
    direct = {"commandId": str(uuid.uuid4()), "expectedCaseRevision": case["revision"],
              "expectedPlaybookRevision": view["playbookRevision"], "taskId": proposal["taskId"],
              "findings": proposal["findings"], "citations": proposal["citations"]}
    expect_failure(409, lambda: req("case", "/v1/cases/" + case["caseId"] + "/steps/bank_document_review/complete", tenant, "POST", direct))
    returned, path = review(case, view, first["runId"], "RETURN_FOR_REWORK", tenant,
                            "Explicitly distinguish the supplied statement's salary claims from independently verified facts.")
    result = req("case", path, tenant, "POST", returned)
    assert result["status"] == "RETURNED" and result["completion"] is None
    assert req("case", path, tenant, "POST", returned) == result
    assert req("agent", "/v1/agent-runs/" + first["runId"], tenant)["status"] == "RETURNED"
    context = req("playbook", "/internal/playbooks/cases/" + case["caseId"] + "/steps/bank_document_review/agent-context", tenant)
    assert returned["reason"] in context["reviewerFeedback"]
    malformed = copy.deepcopy(proposal)
    malformed["runId"] = str(uuid.uuid4())
    malformed["citations"][0]["excerpt"] = "Invented evidence"
    expect_failure(422, lambda: request("case", "/v1/cases/" + case["caseId"] + "/steps/bank_document_review/agent-findings",
                                       "POST", malformed, "agent-negative-test", "agent:bank-document-review", tenant))

    second, findings = run_agent(case, view, tenant)
    assert findings["proposal"]["recommendation"] == "READY_FOR_REVIEW", findings
    accepted, path = review(case, view, second["runId"], "ACCEPT", tenant, "Reviewed the source excerpts and accept the findings for this synthetic case.")
    expect_failure(403, lambda: request("case", path, "POST", accepted, "agent-negative-test", "agent:bank-document-review", tenant))
    expect_failure(409, lambda: req("case", path, tenant, "POST", dict(accepted, expectedCaseRevision=999)))
    ui_review_path = "/v1/playbooks/cases/" + case["caseId"] + "/steps/bank_document_review/agent-review"
    result = req("playbook", ui_review_path, tenant, "POST", accepted)
    assert result["status"] == "ACCEPTED" and result["completion"]["agentRunId"] == second["runId"]
    assert req("case", path, tenant, "POST", accepted) == result
    assert req("agent", "/v1/agent-runs/" + second["runId"], tenant)["status"] == "ACCEPTED"
    assert not any(task["name"] == "Review bank documents" for task in get_playbook(case["caseId"])["humanTasks"])
    assert request("case", "/v1/cases/" + case["caseId"])["status"] == "OPEN"

    # A second case deliberately has insufficient source material.
    incomplete, incomplete_view, _ = prepare(tenant, "Synthetic customer note.\nNo bank statement, account holder, statement period, employer, or salary credit evidence supplied.", "missing-" + run_id)
    missing_run, missing = run_agent(incomplete, incomplete_view, tenant)
    assert missing["proposal"]["recommendation"] == "NEEDS_INFORMATION" and missing["proposal"]["gaps"], missing
    bad_accept, path = review(incomplete, incomplete_view, missing_run["runId"], "ACCEPT", tenant, "Attempting acceptance with missing evidence must fail.")
    expect_failure(409, lambda: req("case", path, tenant, "POST", bad_accept))
    assert any(task["name"] == "Review bank documents" for task in get_playbook(incomplete["caseId"])["humanTasks"])
    output = {"result": "PASS", "provider": "OLLAMA", "model": health["model"], "tenantId": tenant,
              "acceptedCaseId": case["caseId"], "acceptedRunId": second["runId"],
              "missingInformationCaseId": incomplete["caseId"], "missingInformationRunId": missing_run["runId"],
              "reviewUrl": URLS["playbook"] + "/step-workspace.html?caseId=" + case["caseId"] + "&stepId=bank_document_review",
              "missingInformationUrl": URLS["playbook"] + "/step-workspace.html?caseId=" + incomplete["caseId"] + "&stepId=bank_document_review"}
    (Path(__file__).resolve().parents[2] / ".cache/agent-scenario.json").write_text(json.dumps(output, indent=2) + "\n")
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
