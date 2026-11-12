#!/usr/bin/env python3
"""Verify supplied-document citations, task completion, retry safety and linked tasks."""
import copy
import hashlib
import json
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from datetime import datetime, timezone
from smoke import request, eventually, URLS, step
from case_routing_scenarios import expect_failure, route

ACTOR = "investigator-1"
TEXT = "Synthetic bank statement\nSource of funds: salary from Example Employer.\nMonthly salary: 5000 synthetic units."


def req(service, path, tenant, method="GET", payload=None):
    return request(service, path, method, payload, "completion-scenario", ACTOR, tenant)


def accept(tenant, subject, alert_type):
    return request("alert", "/v1/alerts", "POST", {
        "tenantId": tenant, "sourceSystem": "completion-scenario", "sourceAlertId": str(uuid.uuid4()),
        "alertType": alert_type, "subjectId": subject, "subjectVerified": True,
        "detectedAt": datetime.now(timezone.utc).isoformat()}, "completion-scenario")


def get_playbook(case_id):
    return request("playbook", "/v1/playbooks/cases/" + case_id)


def register(case_id, tenant):
    doc = {"documentId": str(uuid.uuid4()), "title": "Supplied synthetic bank statement",
           "sourceReference": "supplied://synthetic-bank-statement", "content": TEXT}
    path = "/v1/playbooks/cases/" + case_id + "/documents"
    result = req("playbook", path, tenant, "POST", doc)
    assert result["sha256"] == hashlib.sha256(TEXT.encode()).hexdigest()
    assert req("playbook", path, tenant, "POST", doc) == result
    expect_failure(409, lambda: req("playbook", path, tenant, "POST", dict(doc, content="Changed content")))
    return doc


def command(view, task_name, case_revision, doc, evidence_type):
    return {"commandId": str(uuid.uuid4()), "expectedCaseRevision": case_revision,
            "expectedPlaybookRevision": view["playbookRevision"],
            "taskId": next(task["id"] for task in view["humanTasks"] if task["name"] == task_name),
            "findings": "The supplied synthetic statement records salary as the source of funds; reviewer confirmation is still required.",
            "citations": [{"evidenceType": evidence_type, "documentId": doc["documentId"],
                           "startLine": 2, "endLine": 2, "excerpt": TEXT.splitlines()[1]}]}


def complete(case_id, step_id, tenant, body):
    return req("case", "/v1/cases/" + case_id + "/steps/" + step_id + "/complete", tenant, "POST", body)


def main():
    run_id = uuid.uuid4().hex[:10]
    tenant, subject = "demo-completion-" + run_id, "customer-" + run_id
    policy = json.loads((Path(__file__).parent / "examples/dynamic-policy.v2.json").read_text())
    policy["tenantId"], policy["version"] = tenant, 1
    request("policy", "/v1/policies/drafts", "POST", policy)
    request("policy", "/v1/policies/drafts/dynamic-kyc-policy/versions/1/publish?tenantId=" + tenant, "POST")
    alert = accept(tenant, subject, "KYC_SOURCE_OF_FUNDS")
    case = eventually(lambda: request("case", "/v1/cases/by-alert/" + alert["alertId"]),
                      lambda value: value["revision"] == 1, "case")
    case_id = case["caseId"]
    view = eventually(lambda: get_playbook(case_id), lambda value: len(value["humanTasks"]) == 3, "dynamic tasks")
    document = register(case_id, tenant)
    body = command(view, "Review bank documents", 1, document, "document_review")
    expect_failure(404, lambda: complete(case_id, "bank_document_review", "different-tenant", body))
    expect_failure(422, lambda: complete(case_id, "bank_document_review", tenant, dict(body, citations=[])))
    bad = copy.deepcopy(body)
    bad["citations"][0]["documentId"] = str(uuid.uuid4())
    expect_failure(422, lambda: complete(case_id, "bank_document_review", tenant, bad))
    bad = copy.deepcopy(body)
    bad["citations"][0]["excerpt"] = "A claim absent from the source."
    expect_failure(422, lambda: complete(case_id, "bank_document_review", tenant, bad))
    expect_failure(409, lambda: complete(case_id, "bank_document_review", tenant, dict(body, expectedCaseRevision=99)))
    wrong_task = next(task["id"] for task in view["humanTasks"] if task["name"] == "Review source of funds")
    expect_failure(422, lambda: complete(case_id, "bank_document_review", tenant, dict(body, taskId=wrong_task)))
    assert req("playbook", "/v1/playbooks/cases/" + case_id + "/completions", tenant) == []
    assert len(get_playbook(case_id)["humanTasks"]) == 3
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: complete(case_id, "bank_document_review", tenant, body), range(2)))
    assert results[0] == results[1]
    receipt = results[0]
    assert receipt["citations"][0]["documentSha256"] == hashlib.sha256(TEXT.encode()).hexdigest()
    assert complete(case_id, "bank_document_review", tenant, body) == receipt
    expect_failure(409, lambda: complete(case_id, "bank_document_review", tenant, dict(body, findings="Changed finding")))
    expect_failure(409, lambda: complete(case_id, "bank_document_review", tenant, dict(body, commandId=str(uuid.uuid4()))))

    # A later alert advances the authoritative case revision while retaining completed work.
    newer = accept(tenant, subject, "KYC_SOURCE_OF_FUNDS")
    case = eventually(lambda: request("case", "/v1/cases/by-alert/" + newer["alertId"]),
                      lambda value: value["revision"] == 2, "new alert case revision")
    assert case["caseId"] == case_id
    view = eventually(lambda: get_playbook(case_id), lambda value: value["caseRevision"] == 2, "new alert reconciliation")
    kyc = command(view, "Review source of funds", 1, document, "source_of_funds_assessment")
    expect_failure(409, lambda: complete(case_id, "source_of_funds_review", tenant, kyc))
    kyc["expectedCaseRevision"] = 2
    complete(case_id, "source_of_funds_review", tenant, kyc)
    assert request("playbook", "/v1/playbooks/cases/" + case_id + "/readiness")["ready"]
    routed = route(case_id, 2, ACTOR, "ESCALATE", "KYC_APPROVER", "approver-2")
    assert route(case_id, routed["revision"], "approver-2", "APPROVE_CLOSE")["status"] == "CLOSED"
    assert complete(case_id, "bank_document_review", tenant, body) == receipt
    audit = req("playbook", "/v1/playbooks/cases/" + case_id + "/completions", tenant)
    assert len(audit) == 2 and all(item["actorId"] == ACTOR for item in audit)

    # Complete linked KYC/transaction reviews through the same evidence-backed API.
    subject2 = "linked-" + run_id
    txn = accept("demo-bank", subject2, "TXN_ANOMALY")
    linked = eventually(lambda: request("case", "/v1/cases/by-alert/" + txn["alertId"]), lambda value: True, "linked case")
    kyc_alert = accept("demo-bank", subject2, "KYC_SOURCE_OF_FUNDS")
    linked = eventually(lambda: request("case", "/v1/cases/by-alert/" + kyc_alert["alertId"]), lambda value: value["revision"] == 2, "linked revision")
    linked_id = linked["caseId"]
    view = eventually(lambda: get_playbook(linked_id), lambda value: value["caseRevision"] == 2, "linked playbook")
    doc2 = register(linked_id, "demo-bank")
    wrong_case = command(view, "Review transactions", 2, document, "transaction_finding")
    expect_failure(422, lambda: complete(linked_id, "transaction_review", "demo-bank", wrong_case))
    complete(linked_id, "transaction_review", "demo-bank", command(view, "Review transactions", 2, doc2, "transaction_finding"))
    view = get_playbook(linked_id)
    assert step(view, "Review combined risk", "available")
    complete(linked_id, "source_of_funds_review", "demo-bank", command(view, "Review source of funds", 2, doc2, "source_of_funds_assessment"))
    assert step(get_playbook(linked_id), "Review combined risk", "active")
    output = {"result": "PASS", "caseId": case_id, "tenantId": tenant, "documentId": document["documentId"],
              "completionCount": len(audit), "linkedCaseId": linked_id,
              "reviewUrl": URLS["playbook"] + "/step-workspace.html?caseId=" + case_id + "&stepId=bank_document_review",
              "auditUrl": URLS["playbook"] + "/v1/playbooks/cases/" + case_id + "/completions"}
    target = Path(__file__).resolve().parents[2] / ".cache/task-completion-scenario.json"
    target.write_text(json.dumps(output, indent=2) + "\n")
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
