#!/usr/bin/env python3
"""Verify policy-driven case handoffs, closure gates and audit history."""

import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

from smoke import URLS, complete_demo_task, eventually, request


def accept(tenant, subject, alert_type, run_id):
    return request("alert", "/v1/alerts", "POST", {
        "tenantId": tenant,
        "sourceSystem": "case-routing-scenario",
        "sourceAlertId": alert_type.lower() + "-" + run_id,
        "alertType": alert_type,
        "subjectId": subject,
        "subjectVerified": True,
        "detectedAt": datetime.now(timezone.utc).isoformat(),
    }, "routing-" + run_id)


def route(case_id, revision, actor, action, to_stage=None, assignee=None, action_id=None):
    return request("case", "/v1/cases/" + case_id + "/actions", "POST", {
        "actionId": action_id or str(uuid.uuid4()),
        "expectedRevision": revision,
        "action": action,
        "toStage": to_stage,
        "assignedTo": assignee,
        "reason": "Synthetic routing scenario verification",
    }, "routing-" + case_id[:8], actor)


def actions(case_id, actor):
    return request("case", "/v1/cases/" + case_id + "/available-actions", actor_id=actor)


def expect_failure(expected_status, action):
    try:
        action()
    except AssertionError as error:
        assert "failed: " + str(expected_status) in str(error), str(error)
        return
    raise AssertionError("Expected HTTP " + str(expected_status))


def kyc_closure():
    run_id = uuid.uuid4().hex[:10]
    alert = accept("demo-expanded-bank", "routing-kyc-" + run_id, "KYC_SOURCE_OF_FUNDS", run_id)
    case = eventually(
        lambda: request("case", "/v1/cases/by-alert/" + alert["alertId"]),
        lambda value: value["caseType"] == "KYC_REVIEW" and value["reviewStage"] == "L1",
        "KYC case at L1",
    )
    case_id = case["caseId"]
    playbook = eventually(
        lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
        lambda value: value["caseRevision"] == 1 and len(value["humanTasks"]) == 1,
        "KYC playbook",
    )
    assert any(option["action"] == "ESCALATE" and option["toStage"] == "L3" for option in actions(case_id, "analyst-1"))
    expect_failure(422, lambda: route(case_id, case["revision"], "analyst-1", "ESCALATE", "L99"))
    routed = route(case_id, case["revision"], "analyst-1", "ESCALATE", "L3", "reviewer-2")
    assert routed["reviewStage"] == "L3" and routed["assignedTo"] == "reviewer-2"
    assert routed["revision"] == 2
    before = next(option for option in actions(case_id, "reviewer-2") if option["action"] == "APPROVE_CLOSE")
    assert not before["enabled"] and "source_of_funds_review" in before["reason"]
    expect_failure(409, lambda: route(case_id, routed["revision"], "reviewer-2", "APPROVE_CLOSE"))

    completed = complete_demo_task(case_id, playbook, "Review source of funds")
    assert completed["status"] == "OPEN"
    self_approval = next(option for option in actions(case_id, "analyst-1") if option["action"] == "APPROVE_CLOSE")
    assert not self_approval["enabled"] and "Independent" in self_approval["reason"]
    approval = next(option for option in actions(case_id, "reviewer-2") if option["action"] == "APPROVE_CLOSE")
    assert approval["enabled"]
    expect_failure(409, lambda: route(case_id, 1, "reviewer-2", "APPROVE_CLOSE"))
    approval_id = str(uuid.uuid4())
    closed = route(case_id, routed["revision"], "reviewer-2", "APPROVE_CLOSE", action_id=approval_id)
    assert closed["status"] == "CLOSED" and closed["revision"] == 3
    assert route(case_id, routed["revision"], "reviewer-2", "APPROVE_CLOSE", action_id=approval_id)["revision"] == 3
    expect_failure(409, lambda: route(case_id, routed["revision"], "reviewer-2", "ESCALATE", "L1",
                                      action_id=approval_id))
    closed_playbook = eventually(
        lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
        lambda value: value["status"] == "CLOSED" and value["humanTasks"] == [],
        "Flowable case closure",
    )
    assert closed_playbook["flowableCaseInstanceId"] == playbook["flowableCaseInstanceId"]
    history = request("case", "/v1/cases/" + case_id + "/actions")
    assert [item["action"] for item in history] == ["ESCALATE", "APPROVE_CLOSE"]
    assert [item["actorId"] for item in history] == ["analyst-1", "reviewer-2"]
    journey = request("case", "/v1/cases/" + case_id + "/journey")
    assert journey["caseRevision"] == 3 and journey["status"] == "CLOSED"
    assert journey["currentStage"] == "L3"
    assert [item["type"] for item in journey["events"]] == [
        "CASE_CREATED", "ESCALATE", "APPROVE_CLOSE"]
    assert journey["events"][1]["assignedTo"] == "reviewer-2"
    assert journey["events"][2]["reason"] == "Synthetic routing scenario verification"
    return {"caseId": case_id, "tenantId": "demo-expanded-bank", "caseType": "KYC_REVIEW",
            "status": "CLOSED", "reviewStage": "L3", "actions": [item["action"] for item in history],
            "caseUrl": URLS["case"] + "/v1/cases/" + case_id,
            "journeyUrl": URLS["case"] + "/case-journey.html?caseId=" + case_id,
            "monitorUrl": URLS["playbook"] + "/playbook-monitor.html?caseId=" + case_id}


def sanctions_rework():
    run_id = uuid.uuid4().hex[:10]
    alert = accept("demo-sanctions-bank", "routing-sanctions-" + run_id, "SANCTIONS_POSSIBLE_MATCH", run_id)
    case = eventually(
        lambda: request("case", "/v1/cases/by-alert/" + alert["alertId"]),
        lambda value: value["reviewStage"] == "SCREENING_ANALYST",
        "sanctions case at screening analyst",
    )
    case_id = case["caseId"]
    assert any(option["toStage"] == "SANCTIONS_SPECIALIST" for option in actions(case_id, "analyst-1"))
    expect_failure(422, lambda: route(case_id, case["revision"], "analyst-1", "APPROVE_CLOSE"))
    specialist = route(case_id, case["revision"], "analyst-1", "ESCALATE", "SANCTIONS_SPECIALIST", "specialist-1")
    assert specialist["reviewStage"] == "SANCTIONS_SPECIALIST"
    assert any(option["action"] == "RETURN_FOR_REWORK" and option["toStage"] == "SCREENING_ANALYST"
               for option in actions(case_id, "specialist-1"))
    returned = route(case_id, specialist["revision"], "specialist-1", "RETURN_FOR_REWORK",
                     "SCREENING_ANALYST", "analyst-2")
    assert returned["reviewStage"] == "SCREENING_ANALYST" and returned["assignedTo"] == "analyst-2"
    assert returned["status"] == "OPEN" and returned["revision"] == 3
    history = request("case", "/v1/cases/" + case_id + "/actions")
    assert [item["action"] for item in history] == ["ESCALATE", "RETURN_FOR_REWORK"]
    journey = request("case", "/v1/cases/" + case_id + "/journey")
    assert [item["type"] for item in journey["events"]] == [
        "CASE_CREATED", "ESCALATE", "RETURN_FOR_REWORK"]
    assert journey["currentStage"] == "SCREENING_ANALYST"
    return {"caseId": case_id, "tenantId": "demo-sanctions-bank", "caseType": "SANCTIONS_REVIEW",
            "status": "OPEN", "reviewStage": returned["reviewStage"],
            "actions": [item["action"] for item in history],
            "caseUrl": URLS["case"] + "/v1/cases/" + case_id,
            "journeyUrl": URLS["case"] + "/case-journey.html?caseId=" + case_id,
            "monitorUrl": URLS["playbook"] + "/playbook-monitor.html?caseId=" + case_id}


def five_level_policy():
    run_id = uuid.uuid4().hex[:10]
    alert = accept("demo-bank", "routing-five-level-" + run_id, "TXN_ANOMALY", run_id)
    case = eventually(
        lambda: request("case", "/v1/cases/by-alert/" + alert["alertId"]),
        lambda value: value["reviewStage"] == "L1" and value["caseType"] == "TRANSACTION_REVIEW",
        "baseline case at L1",
    )
    case_id = case["caseId"]
    eventually(
        lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
        lambda value: value["caseRevision"] == 1,
        "baseline playbook",
    )
    targets = {option["toStage"] for option in actions(case_id, "analyst-1") if option["action"] == "ESCALATE"}
    assert targets == {"L2", "L3", "L4", "L5"}
    routed = route(case_id, case["revision"], "analyst-1", "ESCALATE", "L5", "reviewer-5")
    assert routed["reviewStage"] == "L5" and routed["assignedTo"] == "reviewer-5"
    close_option = next(option for option in actions(case_id, "reviewer-5") if option["action"] == "APPROVE_CLOSE")
    assert not close_option["enabled"] and "Required playbook steps" in close_option["reason"]
    returned = route(case_id, routed["revision"], "reviewer-5", "RETURN_FOR_REWORK", "L1", "analyst-2")
    assert returned["reviewStage"] == "L1" and returned["status"] == "OPEN"
    return {"caseId": case_id, "tenantId": "demo-bank", "caseType": "TRANSACTION_REVIEW",
            "status": "OPEN", "reviewStage": returned["reviewStage"],
            "actions": ["ESCALATE", "RETURN_FOR_REWORK"],
            "caseUrl": URLS["case"] + "/v1/cases/" + case_id,
            "journeyUrl": URLS["case"] + "/case-journey.html?caseId=" + case_id,
            "monitorUrl": URLS["playbook"] + "/playbook-monitor.html?caseId=" + case_id}


def main():
    output = {"result": "PASS", "cases": [kyc_closure(), sanctions_rework(), five_level_policy()]}
    target = Path(__file__).resolve().parents[2] / ".cache" / "case-routing-scenarios.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, indent=2))
    print("Saved " + str(target))


if __name__ == "__main__":
    main()
