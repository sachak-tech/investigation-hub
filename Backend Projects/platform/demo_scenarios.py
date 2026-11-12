#!/usr/bin/env python3
"""Idempotently create six synthetic cases across two seeded demo policies."""

import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

from smoke import URLS, eventually, request


SCENARIOS = [
    {
        "name": "KYC source-of-funds only",
        "slug": "kyc-only",
        "tenantId": "demo-expanded-bank",
        "alerts": ["KYC_SOURCE_OF_FUNDS"],
        "caseType": "KYC_REVIEW",
        "steps": ["source_of_funds_review"],
    },
    {
        "name": "KYC followed by PEP and adverse media",
        "slug": "kyc-pep",
        "tenantId": "demo-expanded-bank",
        "alerts": ["KYC_SOURCE_OF_FUNDS", "PEP_ADVERSE_MEDIA"],
        "caseType": "KYC_AND_PEP_REVIEW",
        "steps": ["source_of_funds_review", "pep_adverse_media_review"],
    },
    {
        "name": "Transaction followed by sanctions match",
        "slug": "transaction-sanctions",
        "tenantId": "demo-expanded-bank",
        "alerts": ["TXN_ANOMALY", "SANCTIONS_POSSIBLE_MATCH"],
        "caseType": "TRANSACTION_AND_SANCTIONS_REVIEW",
        "steps": ["transaction_review", "agent_transaction_analysis", "sanctions_match_review", "agent_sanctions_enrichment"],
    },
    {
        "name": "Transaction followed by KYC and PEP alerts",
        "slug": "transaction-kyc-pep",
        "tenantId": "demo-expanded-bank",
        "alerts": ["TXN_ANOMALY", "KYC_SOURCE_OF_FUNDS", "PEP_ADVERSE_MEDIA"],
        "caseType": "TRANSACTION_KYC_AND_PEP_REVIEW",
        "steps": ["transaction_review", "agent_transaction_analysis", "source_of_funds_review", "combined_risk_review", "pep_adverse_media_review", "cross_risk_assessment"],
    },
    {
        "name": "Sanctions screening only",
        "slug": "sanctions-only",
        "tenantId": "demo-sanctions-bank",
        "alerts": ["SANCTIONS_POSSIBLE_MATCH"],
        "caseType": "SANCTIONS_REVIEW",
        "steps": ["sanctions_match_review", "agent_sanctions_enrichment"],
    },
    {
        "name": "PEP followed by sanctions match",
        "slug": "pep-sanctions",
        "tenantId": "demo-sanctions-bank",
        "alerts": ["PEP_ADVERSE_MEDIA", "SANCTIONS_POSSIBLE_MATCH"],
        "caseType": "SANCTIONS_AND_PEP_REVIEW",
        "steps": ["sanctions_match_review", "agent_sanctions_enrichment", "pep_adverse_media_review", "cross_risk_assessment"],
    },
]


def main():
    results = []
    detected_at = datetime(2026, 11, 12, tzinfo=timezone.utc).isoformat()
    for scenario in SCENARIOS:
        alert_ids = []
        subject_id = "demo-seed-v1-" + scenario["slug"]
        case_id = None
        for index, alert_type in enumerate(scenario["alerts"], start=1):
            alert = request("alert", "/v1/alerts", "POST", {
                "tenantId": scenario["tenantId"],
                "sourceSystem": "demo-scenario-seed",
                "sourceAlertId": scenario["slug"] + "-" + str(index) + "-v1",
                "alertType": alert_type,
                "subjectId": subject_id,
                "subjectVerified": True,
                "detectedAt": detected_at,
            }, "demo-" + scenario["slug"])
            alert_ids.append(alert["alertId"])
            linked = eventually(
                lambda alert_id=alert["alertId"]: request("case", "/v1/cases/by-alert/" + alert_id),
                lambda value, count=index: value["revision"] >= count,
                scenario["slug"] + " case for alert " + alert["alertId"],
            )
            if case_id is None:
                case_id = linked["caseId"]
            assert linked["caseId"] == case_id, scenario["slug"]
            eventually(
                lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
                lambda value, count=index: value["caseRevision"] >= count,
                scenario["slug"] + " playbook after alert " + str(index),
            )

        cases = [request("case", "/v1/cases/by-alert/" + alert_id) for alert_id in alert_ids]
        assert all(case["caseId"] == case_id for case in cases), scenario["slug"]
        assert all(case["caseType"] == scenario["caseType"] and case["revision"] == len(alert_ids)
                   for case in cases), scenario["slug"]
        playbook = eventually(
            lambda: request("playbook", "/v1/playbooks/cases/" + case_id),
            lambda value, expected=scenario["steps"], count=len(alert_ids):
                value["caseRevision"] == count
                and 1 <= value["playbookRevision"] <= count
                and set(value["requiredStepIds"]) == set(expected)
                and all(any(item["definitionId"] == DEFINITION_IDS[step]
                                and item["state"] in ("active", "available") for item in value["steps"])
                        for step in expected)
                and {item["definitionId"] for item in value["steps"] if item["state"] == "active"}
                    <= {DEFINITION_IDS[step] for step in expected},
            scenario["slug"] + " playbook",
        )
        results.append({
            "name": scenario["name"],
            "tenantId": scenario["tenantId"],
            "alertTypes": scenario["alerts"],
            "alertIds": alert_ids,
            "caseId": case_id,
            "caseType": cases[0]["caseType"],
            "caseRevision": cases[0]["revision"],
            "playbookRevision": playbook["playbookRevision"],
            "activeSteps": [step["name"] for step in playbook["steps"] if step["state"] == "active"],
            "monitorUrl": URLS["playbook"] + "/playbook-monitor.html?caseId=" + quote(case_id),
        })

    output = {"result": "PASS", "caseCount": len(results),
              "alertCount": sum(len(result["alertIds"]) for result in results), "cases": results}
    target = Path(__file__).resolve().parents[2] / ".cache" / "demo-scenarios.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, indent=2))
    print("Saved " + str(target))


DEFINITION_IDS = {
    "transaction_review": "transactionReviewTask",
    "agent_transaction_analysis": "agentAnalysisTask",
    "source_of_funds_review": "kycReviewTask",
    "combined_risk_review": "combinedReviewTask",
    "sanctions_match_review": "sanctionsReviewTask",
    "agent_sanctions_enrichment": "sanctionsEnrichmentTask",
    "pep_adverse_media_review": "pepReviewTask",
    "cross_risk_assessment": "crossRiskTask",
}


if __name__ == "__main__":
    main()
