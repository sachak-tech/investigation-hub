#!/usr/bin/env python3
"""Exercise the Milestone 0 active-case scenario against a running local spike."""

import json
import os
import subprocess
import sys
import time
import urllib.request
import uuid


BASE_URL = os.environ.get("SPIKE_BASE_URL", "http://127.0.0.1:18080/spike")


def request(path, method="GET", payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {} if data is None else {"Content-Type": "application/json"}
    req = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as response:
        content = response.read()
        return json.loads(content) if content else None


def find(items, name, state=None):
    for item in items:
        if item["name"] == name and (state is None or item["state"] == state):
            return item
    raise AssertionError("Missing step: {} ({}) in {}".format(name, state, items))


def wait_for(path, predicate, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = request(path)
        if predicate(value):
            return value
        time.sleep(0.1)
    raise AssertionError("Timed out waiting for " + path)


def main():
    product_case_id = "CASE-" + uuid.uuid4().hex[:8]
    started = request("/cases", "POST", {"caseId": product_case_id})
    case_id = started["flowableCaseInstanceId"]
    base = "/cases/" + case_id

    initial = request(base)
    assert initial["caseType"] == "TRANSACTION_REVIEW"
    find(initial["steps"], "Review transactions", "active")
    find(initial["steps"], "Agent transaction analysis", "active")
    find(initial["steps"], "Review source of funds", "available")

    subprocess.run([sys.executable, "worker.py"], check=True, env=os.environ.copy())
    after_agent = wait_for(base, lambda value: value["agentFinding"] is not None)
    assert after_agent["agentFinding"] == "Synthetic transaction analysis completed by Python worker"
    transaction_task = next(t for t in after_agent["humanTasks"] if t["name"] == "Review transactions")
    request("/tasks/" + transaction_task["id"] + "/complete", "POST")

    with_kyc = request(base + "/kyc-alert", "POST")
    assert with_kyc["caseType"] == "TRANSACTION_AND_KYC_REVIEW"
    find(with_kyc["steps"], "Review source of funds", "active")

    with_extra = request(base + "/extra-step", "POST")
    find(with_extra["steps"], "Review external source", "active")
    assert {t["name"] for t in with_extra["humanTasks"]} == {
        "Review source of funds", "Review external source"
    }

    history = request(base + "/history")
    find(history, "Review transactions", "completed")
    find(history, "Agent transaction analysis", "completed")
    find(history, "Review source of funds", "active")
    find(history, "Review external source", "active")

    print(json.dumps({
        "result": "PASS",
        "productCaseId": product_case_id,
        "flowableCaseInstanceId": case_id,
        "classification": with_extra["caseType"],
        "completedPreserved": ["Review transactions", "Agent transaction analysis"],
        "newActiveSteps": ["Review source of funds", "Review external source"]
    }, indent=2))


if __name__ == "__main__":
    main()

