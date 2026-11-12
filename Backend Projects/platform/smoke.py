#!/usr/bin/env python3
"""Verify the first four-service synthetic alert-to-Flowable path."""

import json
import os
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone


URLS = {
    "policy": os.environ.get("IH_POLICY_URL", "http://127.0.0.1:18081"),
    "case": os.environ.get("IH_CASE_URL", "http://127.0.0.1:18082"),
    "playbook": os.environ.get("IH_PLAYBOOK_URL", "http://127.0.0.1:18083"),
    "alert": os.environ.get("IH_ALERT_URL", "http://127.0.0.1:18084"),
}


def request(service, path, method="GET", payload=None, correlation_id=None, actor_id=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {} if data is None else {"Content-Type": "application/json"}
    if correlation_id:
        headers["X-Correlation-Id"] = correlation_id
    if actor_id:
        headers["X-Actor-Id"] = actor_id
    req = urllib.request.Request(URLS[service] + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            body = response.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        raise AssertionError("{} {} failed: {} {}".format(service, path, error.code,
                                                        error.read().decode("utf-8"))) from error


def eventually(action, check, label, timeout=20):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            value = action()
            if check(value):
                return value
        except (urllib.error.HTTPError, urllib.error.URLError, AssertionError) as error:
            last_error = error
        time.sleep(0.2)
    raise AssertionError("Timed out waiting for {}: {}".format(label, last_error))


def step(view, name, state):
    return any(item["name"] == name and item["state"] == state for item in view["steps"])


def complete_demo_task(case_id, view, name):
    task = next(item for item in view["humanTasks"] if item["name"] == name)
    return request("playbook", "/v1/playbooks/demo/cases/" + case_id
                   + "/tasks/" + task["id"] + "/complete", "POST")


def main():
    for service in URLS:
        assert request(service, "/actuator/health/readiness")["status"] == "UP"
        assert request(service, "/v3/api-docs")["openapi"].startswith("3.")

    run_id = uuid.uuid4().hex[:12]
    correlation_id = "milestone1-" + run_id
    subject_id = "synthetic-subject-" + run_id
    detected_at = datetime.now(timezone.utc).isoformat()

    transaction = {
        "tenantId": "demo-bank", "sourceSystem": "synthetic-monitor",
        "sourceAlertId": "txn-" + run_id, "alertType": "TXN_ANOMALY",
        "subjectId": subject_id, "subjectVerified": True, "detectedAt": detected_at,
    }
    first = request("alert", "/v1/alerts", "POST", transaction, correlation_id)
    duplicate = request("alert", "/v1/alerts", "POST", transaction, correlation_id)
    assert first["alertId"] == duplicate["alertId"]
    assert first["acceptedEventId"] == duplicate["acceptedEventId"]

    case = eventually(
        lambda: request("case", "/v1/cases/by-alert/" + first["alertId"]),
        lambda value: value["caseType"] == "TRANSACTION_REVIEW" and value["revision"] == 1,
        "transaction case",
    )
    case_id = case["caseId"]
    initial_playbook = eventually(
        lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
        lambda value: value["caseRevision"] == 1 and step(value, "Review transactions", "active"),
        "initial Flowable playbook",
    )
    assert initial_playbook["requiredStepIds"] == ["transaction_review", "agent_transaction_analysis"]
    assert step(initial_playbook, "Review source of funds", "available")

    kyc = dict(transaction, sourceAlertId="kyc-" + run_id, alertType="KYC_SOURCE_OF_FUNDS")
    second = request("alert", "/v1/alerts", "POST", kyc, correlation_id)
    combined_case = eventually(
        lambda: request("case", "/v1/cases/by-alert/" + second["alertId"]),
        lambda value: value["revision"] == 2 and value["caseType"] == "TRANSACTION_AND_KYC_REVIEW",
        "combined case classification",
    )
    assert combined_case["caseId"] == case_id
    assert "combined_risk_review" in combined_case["requiredStepIds"]
    revised = eventually(
        lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
        lambda value: value["caseRevision"] == 2 and value["playbookRevision"] == 2
        and step(value, "Review source of funds", "active")
        and step(value, "Review combined risk", "available"),
        "revised Flowable playbook",
    )
    assert revised["flowableCaseInstanceId"] == initial_playbook["flowableCaseInstanceId"]
    assert request("case", "/v1/cases/" + case_id)["revision"] == 2
    journey = request("case", "/v1/cases/" + case_id + "/journey")
    assert journey["caseRevision"] == 2
    assert journey["caseType"] == "TRANSACTION_AND_KYC_REVIEW"
    assert [event["type"] for event in journey["events"]] == [
        "CASE_CREATED", "ALERT_LINKED", "CASE_CLASSIFICATION_CHANGED"]
    assert [event["alertType"] for event in journey["events"][:2]] == [
        "TXN_ANOMALY", "KYC_SOURCE_OF_FUNDS"]
    assert journey["events"][2]["fromCaseType"] == "TRANSACTION_REVIEW"
    assert journey["events"][2]["toCaseType"] == "TRANSACTION_AND_KYC_REVIEW"

    after_kyc = complete_demo_task(case_id, revised, "Review source of funds")
    assert step(after_kyc, "Review combined risk", "available")
    after_transaction = complete_demo_task(case_id, after_kyc, "Review transactions")
    assert step(after_transaction, "Review combined risk", "active")

    print(json.dumps({
        "result": "PASS", "correlationId": correlation_id,
        "firstAlertEventId": first["acceptedEventId"],
        "secondAlertEventId": second["acceptedEventId"],
        "caseId": case_id, "caseRevision": 2, "playbookRevision": 2,
        "flowableCaseInstanceId": after_transaction["flowableCaseInstanceId"],
        "activeSteps": [item["name"] for item in after_transaction["steps"] if item["state"] == "active"],
    }, indent=2))


if __name__ == "__main__":
    main()
