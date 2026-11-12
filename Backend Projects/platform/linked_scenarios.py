#!/usr/bin/env python3
"""Create and verify two synthetic cases with CMMN task dependencies."""

import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

from smoke import URLS, complete_demo_task, eventually, request, step


def assert_step(view, name, state):
    assert step(view, name, state), "Expected {!r} to be {!r}; got {}".format(name, state, view["steps"])


def create_case(slug, alert_types, expected_types):
    run_id = uuid.uuid4().hex[:10]
    subject_id = "linked-demo-" + slug + "-" + run_id
    detected_at = datetime.now(timezone.utc).isoformat()
    case_id = None
    for index, (alert_type, case_type) in enumerate(zip(alert_types, expected_types), start=1):
        alert = request("alert", "/v1/alerts", "POST", {
            "tenantId": "demo-expanded-bank",
            "sourceSystem": "linked-scenario-seed",
            "sourceAlertId": slug + "-" + run_id + "-" + str(index),
            "alertType": alert_type,
            "subjectId": subject_id,
            "subjectVerified": True,
            "detectedAt": detected_at,
        }, "linked-demo-" + run_id)
        case = eventually(
            lambda: request("case", "/v1/cases/by-alert/" + alert["alertId"]),
            lambda value: value["revision"] == index and value["caseType"] == case_type,
            slug + " case revision " + str(index),
        )
        if case_id is None:
            case_id = case["caseId"]
        assert case["caseId"] == case_id
        eventually(
            lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
            lambda value: value["caseRevision"] == index,
            slug + " playbook revision " + str(index),
        )
    return case_id


def get_playbook(case_id):
    return request("playbook", "/v1/playbooks/cases/" + case_id)


def monitor_url(case_id):
    return URLS["playbook"] + "/playbook-monitor.html?caseId=" + quote(case_id)


def linked_case():
    case_id = create_case(
        "transaction-kyc-linked",
        ["TXN_ANOMALY", "KYC_SOURCE_OF_FUNDS"],
        ["TRANSACTION_REVIEW", "TRANSACTION_AND_KYC_REVIEW"],
    )
    view = get_playbook(case_id)
    assert_step(view, "Review transactions", "active")
    assert_step(view, "Review source of funds", "active")
    assert_step(view, "Review combined risk", "available")
    assert_step(view, "Agent transaction analysis", "active")

    after_kyc = complete_demo_task(case_id, view, "Review source of funds")
    assert_step(after_kyc, "Review combined risk", "available")
    assert_step(after_kyc, "Review transactions", "active")

    after_transaction = complete_demo_task(case_id, after_kyc, "Review transactions")
    assert_step(after_transaction, "Review combined risk", "active")
    assert_step(after_transaction, "Agent transaction analysis", "active")
    return {
        "scenario": "Linked review: transaction and KYC reviews must finish before combined risk review",
        "caseId": case_id,
        "caseType": "TRANSACTION_AND_KYC_REVIEW",
        "verifiedTransitions": [
            "Combined risk available while both reviews are active",
            "Combined risk still available after source-of-funds review completes",
            "Combined risk active after transaction review also completes",
        ],
        "monitorUrl": monitor_url(case_id),
    }


def linked_waiting_case():
    case_id = create_case(
        "transaction-kyc-waiting",
        ["TXN_ANOMALY", "KYC_SOURCE_OF_FUNDS"],
        ["TRANSACTION_REVIEW", "TRANSACTION_AND_KYC_REVIEW"],
    )
    view = get_playbook(case_id)
    assert_step(view, "Review transactions", "active")
    assert_step(view, "Review source of funds", "active")
    assert_step(view, "Review combined risk", "available")
    return {
        "scenario": "Linked review waiting: combined risk is available until transaction and KYC reviews finish",
        "caseId": case_id,
        "caseType": "TRANSACTION_AND_KYC_REVIEW",
        "verifiedTransitions": ["Combined risk remains available; both prerequisite reviews are active"],
        "monitorUrl": monitor_url(case_id),
    }


def mixed_case():
    case_id = create_case(
        "transaction-kyc-pep-mixed",
        ["TXN_ANOMALY", "KYC_SOURCE_OF_FUNDS", "PEP_ADVERSE_MEDIA"],
        ["TRANSACTION_REVIEW", "TRANSACTION_AND_KYC_REVIEW", "TRANSACTION_KYC_AND_PEP_REVIEW"],
    )
    view = get_playbook(case_id)
    for name in ["Review transactions", "Review source of funds", "Review PEP and adverse media",
                 "Agent transaction analysis"]:
        assert_step(view, name, "active")
    assert_step(view, "Review combined risk", "available")
    assert_step(view, "Review cross-signal risk", "available")

    after_transaction = complete_demo_task(case_id, view, "Review transactions")
    assert_step(after_transaction, "Review combined risk", "available")
    after_kyc = complete_demo_task(case_id, after_transaction, "Review source of funds")
    assert_step(after_kyc, "Review combined risk", "active")
    assert_step(after_kyc, "Review cross-signal risk", "available")
    assert_step(after_kyc, "Review PEP and adverse media", "active")

    after_combined = complete_demo_task(case_id, after_kyc, "Review combined risk")
    assert_step(after_combined, "Review cross-signal risk", "available")
    after_pep = complete_demo_task(case_id, after_combined, "Review PEP and adverse media")
    assert_step(after_pep, "Review cross-signal risk", "active")
    assert_step(after_pep, "Agent transaction analysis", "active")
    return {
        "scenario": "Mixed review: parallel transaction, KYC, PEP and agent work; gated combined and cross-signal reviews",
        "caseId": case_id,
        "caseType": "TRANSACTION_KYC_AND_PEP_REVIEW",
        "verifiedTransitions": [
            "Four independent steps active while both aggregate reviews are available",
            "Combined risk active only after transaction and KYC reviews complete",
            "Cross-signal risk still available after combined review completes",
            "Cross-signal risk active after PEP review also completes",
        ],
        "monitorUrl": monitor_url(case_id),
    }


def mixed_waiting_case():
    case_id = create_case(
        "transaction-kyc-pep-waiting",
        ["TXN_ANOMALY", "KYC_SOURCE_OF_FUNDS", "PEP_ADVERSE_MEDIA"],
        ["TRANSACTION_REVIEW", "TRANSACTION_AND_KYC_REVIEW", "TRANSACTION_KYC_AND_PEP_REVIEW"],
    )
    view = get_playbook(case_id)
    after_transaction = complete_demo_task(case_id, view, "Review transactions")
    after_kyc = complete_demo_task(case_id, after_transaction, "Review source of funds")
    assert_step(after_kyc, "Review combined risk", "active")
    after_combined = complete_demo_task(case_id, after_kyc, "Review combined risk")
    assert_step(after_combined, "Review cross-signal risk", "available")
    assert_step(after_combined, "Review PEP and adverse media", "active")
    assert_step(after_combined, "Agent transaction analysis", "active")
    return {
        "scenario": "Mixed review waiting: PEP and agent tasks remain independent while cross-signal review waits for PEP",
        "caseId": case_id,
        "caseType": "TRANSACTION_KYC_AND_PEP_REVIEW",
        "verifiedTransitions": [
            "Transaction, KYC and combined reviews completed",
            "PEP review and agent work remain active",
            "Cross-signal risk remains available until PEP review completes",
        ],
        "monitorUrl": monitor_url(case_id),
    }


def main():
    results = [linked_waiting_case(), linked_case(), mixed_waiting_case(), mixed_case()]
    output = {"result": "PASS", "caseCount": 4, "alertCount": 10, "cases": results}
    target = Path(__file__).resolve().parents[2] / ".cache" / "linked-scenarios.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, indent=2))
    print("Saved " + str(target))


if __name__ == "__main__":
    main()
